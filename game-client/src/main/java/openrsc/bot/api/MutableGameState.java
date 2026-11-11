package openrsc.bot.api;

import it.unimi.dsi.fastutil.ints.IntIterator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import openrsc.bot.api.Features.IronmanHud;
import openrsc.bot.api.Features.KillAnnouncement;
import openrsc.bot.api.Features.TrawlerHud;
import openrsc.bot.api.Shop.ShopNpc;
import openrsc.bot.api.codec.WireLayout;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Holdings;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.packets.TradeOfferPacket;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.bot.core.login.ServerRulesCheck;
import openrsc.bot.core.world.CombatPairTracker;
import openrsc.bot.core.world.Opcode;
import openrsc.bot.core.world.RenderEvent;
import openrsc.bot.core.world.TailDue;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.gamedata.world.WallOverlay;
import openrsc.nav.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * The game state one session builds up from the server's packets, frozen into a {@link GameState} once
 * per tick by {@link #freezeTick}.
 */
public class MutableGameState implements SentNotes {

    private final MutableInventory inventory = new MutableInventory();
    private final MutableBank bank = new MutableBank();
    private final MutableStats stats = new MutableStats();
    private final MutableQuests quests = new MutableQuests();
    private volatile boolean questTableKnown;
    private volatile boolean questTableFresh;
    private final BitSet questStagesUpdated = new BitSet();

    private boolean questTablesFromSnapshot;

    private final ServerData serverData;

    private final boolean auctionHouse;

    private SessionTick sessionTick;
    private Instant serverTickTime = Instant.EPOCH;
    private java.util.function.LongSupplier clockMs = System::currentTimeMillis;
    private java.util.function.LongSupplier clockNanos = System::nanoTime;

    private Point pos = Point.UNKNOWN;
    private boolean bankObserved;

    public void setBankObserved(boolean observed) {
        this.bankObserved = observed;
    }

    public boolean bankObserved() {
        return bankObserved;
    }

    private final MutableDialog dialog;
    private final MutableMessages messages = new MutableMessages();
    private final MutableShop shop = new MutableShop();
    private final MutableAuctionHouse auction = new MutableAuctionHouse();
    private boolean bankOpen;
    private long bankCloses;
    private final MutableBankOps bankOps = new MutableBankOps();

    private long serverTickNanos;
    private final MutableFatigue fatigue = new MutableFatigue();
    private final MutableTrade trade = new MutableTrade();
    private final MutableTickEvents tickEvents = new MutableTickEvents();
    private final MutableHud hud = new MutableHud();
    private final MutableDuel duel = new MutableDuel();
    private final MutableClan clan = new MutableClan();
    private final MutableParty party = new MutableParty();
    private final MutableSelfPanel selfPanel = new MutableSelfPanel();
    private final MutableSocial social = new MutableSocial();
    private final MutableFeatures features = new MutableFeatures();
    private final MutableRegion region = new MutableRegion();

    public MutableHud hud() {
        return hud;
    }

    public MutableDuel duel() {
        return duel;
    }

    public MutableClan clan() {
        return clan;
    }

    public MutableParty party() {
        return party;
    }

    public MutableSelfPanel selfPanel() {
        return selfPanel;
    }

    public MutableSocial social() {
        return social;
    }

    public MutableFeatures features() {
        return features;
    }

    public MutableRegion region() {
        return region;
    }

    private final WallOverlay losOverlay;

    private final @Nullable Planner nav;

    private final MutableWorld world = new MutableWorld(this::footprint);

    /** @param nav the planner {@link GameState#nav} answers with; null for a state that plans nothing */
    public MutableGameState(ServerData serverData, SessionKey session, WallOverlay losOverlay, @Nullable Planner nav) {
        this.serverData = Objects.requireNonNull(serverData, "serverData");
        this.dialog = new MutableDialog(Dialog.inDialogGap(serverData));
        this.auctionHouse = Extension.AUCTION_HOUSE.on(serverData.world());
        this.sessionTick = new SessionTick(Objects.requireNonNull(session, "session"), 0);
        this.auction.loggedIn(this.sessionTick);
        this.username = session.username();
        this.losOverlay = Objects.requireNonNull(losOverlay, "losOverlay");
        this.nav = nav;
        this.gridRadius = serverData.world().liveViewGridRadius();
        bank.setCapacity(serverData.world().rules().bankCapacity());
    }

    public ServerData serverData() {
        return serverData;
    }

    public boolean customQuestLocations() {
        return serverData.world().has(openrsc.gamedata.WorldProfile.Feature.CUSTOM_QUESTS_LOCS);
    }

    public MutableInventory inventory() {
        return inventory;
    }

    public MutableBank bank() {
        return bank;
    }

    public MutableStats stats() {
        return stats;
    }

    public MutableQuests quests() {
        return quests;
    }

    public void setPos(Point pos) {
        this.pos = Objects.requireNonNull(pos, "pos");
    }

    public boolean hasPosition() {
        return !pos.equals(Point.UNKNOWN);
    }

    public MutableDialog dialog() {
        return dialog;
    }

    public MutableMessages messages() {
        return messages;
    }

    public MutableShop shop() {
        return shop;
    }

    public MutableAuctionHouse auction() {
        return auction;
    }

    public void setBankOpen(boolean v) {
        setBankScreenOpen(v);
    }

    public void setServerTickNanos(long v) {
        this.serverTickNanos = v;
    }

    public MutableFatigue fatigue() {
        return fatigue;
    }

    public MutableTrade trade() {
        return trade;
    }

    public MutableTickEvents tickEvents() {
        return tickEvents;
    }

    private int hitsThisFight;

    public void noteCombatTick() {
        boolean tookDamage = tickEvents.snapshot().tookDamage()
                && !Messages.breathedOn(messages.snapshot().thisTickText());
        hitsThisFight = inCombat() ? (tookDamage ? hitsThisFight + 1 : hitsThisFight) : 0;
        if (inCombat()) {
            auction.fought();
        }
    }

    private SessionTick attackSentTick;
    private int attackTargetIndex = -1;
    private final LinkedHashMap<Integer, Integer> attackSentTickByIndex = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<Integer, Integer> eldest) {
            return size() > 64;
        }
    };

    @Override
    public void noteAttackSent(SessionTick now, int targetIndex) {
        this.attackSentTick = now;
        this.attackTargetIndex = targetIndex;
        if (now != null) {
            attackSentTickByIndex.put(targetIndex, now.tick());
        }
    }

    private @Nullable Map<TickKey<?>, Object> noteFrame;

    private final Object notesLock = new Object();
    private volatile TickNotes tickNotes = TickNotes.EMPTY;

    @Override
    public <T> void note(TickKey<T> key, T value) {
        synchronized (notesLock) {
            Map<TickKey<?>, Object> frame = noteFrame;
            if (frame == null) {
                frame = new HashMap<>(4);
                noteFrame = frame;
            }
            frame.put(key, value);
        }
    }

    public void publishTickNotes() {
        synchronized (notesLock) {
            Map<TickKey<?>, Object> frame = noteFrame;
            noteFrame = null;
            tickNotes = frame == null ? TickNotes.EMPTY : new TickNotes(frame);
        }
    }

    public TickNotes tickNotes() {
        return tickNotes;
    }

    private GameState.@Nullable SentWalk walkSent;

    @Override
    public void noteWalkSent(int tick, List<Point> waypoints) {
        this.walkSent = new GameState.SentWalk(tick, List.copyOf(waypoints));
    }

    private int styleSent = -1;
    private List<String> describedLastTick = List.of();

    public void setDescribedLastTick(List<String> lines) {
        this.describedLastTick = lines == null ? List.of() : List.copyOf(lines);
    }

    private int selfActionBubbleTick = -1;
    private int selfActionBubbleItemId;

    private final Set<Integer> prayersSentOn = new HashSet<>();

    @Override
    public void notePrayerSent(int prayerId, boolean on) {
        if (on) {
            prayersSentOn.add(prayerId);
        } else {
            prayersSentOn.remove(prayerId);
        }
    }

    @Override
    public void noteStyleSent(int style) {
        this.styleSent = style;
    }

    @Override
    public void notePrivacySent(int blockChat, int blockPrivate, int blockTrade, int blockDuel) {
        hud.privacy(blockChat, blockPrivate, blockTrade, blockDuel);
    }

    private SessionTick castSentTick;
    private int castSpellId = -1;
    private int castTargetIndex = -1;

    @Override
    public void noteCastSent(SessionTick now, int spellId, int targetIndex) {
        this.castSentTick = now;
        this.castSpellId = spellId;
        this.castTargetIndex = targetIndex;
    }

    private SessionTick useOnScenerySentTick;
    private int useOnSceneryItemId = -1;

    static final int USE_ON_SCENERY_MEMORY_TICKS = 6;

    @Override
    public void noteUseOnScenerySent(SessionTick now, int slot) {
        this.useOnScenerySentTick = now;
        this.useOnSceneryItemId = -1;
        for (InventorySlot s : inventory.snapshot().slots()) {
            if (s.slot() == slot) {
                this.useOnSceneryItemId = s.item().id();
                break;
            }
        }
    }

    private SessionTick itemCommandSentTick;
    private int itemCommandItemId = -1;

    @Override
    public void noteItemCommandSent(SessionTick now, int slot) {
        this.itemCommandSentTick = now;
        this.itemCommandItemId = -1;
        for (InventorySlot s : inventory.snapshot().slots()) {
            if (s.slot() == slot) {
                this.itemCommandItemId = s.item().id();
                break;
            }
        }
    }

    @Override
    public void noteWornItemCommandSent(SessionTick now, int itemId) {
        this.itemCommandSentTick = now;
        this.itemCommandItemId = itemId;
    }

    public int itemUsedOnScenery() {
        if (useOnScenerySentTick == null || useOnSceneryItemId < 0) {
            return -1;
        }
        return sessionTick.since(useOnScenerySentTick) <= USE_ON_SCENERY_MEMORY_TICKS ? useOnSceneryItemId : -1;
    }

    private boolean inAppearanceScreen;
    private boolean appearanceEditorOpen;
    private boolean onTutorial;

    public void setTutorial(boolean inAppearanceScreen, boolean onTutorial) {
        this.inAppearanceScreen = inAppearanceScreen;
        this.appearanceEditorOpen = inAppearanceScreen;
        this.onTutorial = onTutorial;
    }

    public void setHitsThisFight(int hits) {
        this.hitsThisFight = hits;
    }

    private int selfServerIndex = -1;

    public void setSelfServerIndex(int selfServerIndex) {
        this.selfServerIndex = selfServerIndex;
    }

    private final String username;

    private long deaths;
    private Point deathTile;

    private int rangeArmTick = Integer.MIN_VALUE;
    private int rangeKillTick = Integer.MIN_VALUE;
    private int rangeTargetIndex = -1;

    @Override
    public void noteRangeArmed(int targetServerIndex, int tick) {
        this.rangeArmTick = tick;
        this.rangeTargetIndex = targetServerIndex;
    }

    @Override
    public void noteRangeKilled(int tick) {
        this.rangeKillTick = tick;
    }

    @Override
    public void notePresetSaved(int slot) {
        if (slot < 0 || slot >= WindowMove.PRESETS) {
            return;
        }
        List<InventorySlot> worn = new ArrayList<>();
        for (WornItem w : selfPanel().snapshot().equipment()) {
            worn.add(new InventorySlot(w.wieldPos(), w.catalogId(), w.amount(), false));
        }
        features().bankPreset(new BankPreset(slot, inventory.snapshot().slots(), worn));
    }

    private int selfRetreatTick = Integer.MIN_VALUE;

    private int poisonDamage;
    private SessionTick lastPoisonHitTick;

    public void setPoison(int damage, SessionTick lastHit) {
        this.poisonDamage = damage;
        this.lastPoisonHitTick = lastHit;
    }

    private String lastSelfChat;
    private int lastSelfChatTick = Integer.MIN_VALUE;

    public void setDeath(long deaths, Point deathTile) {
        this.deaths = deaths;
        this.deathTile = deathTile;
    }

    private StaticLocIndex statics() {
        return serverData.collisionMap().staticLocIndex();
    }

    private Rect footprint(GameObject obj) {
        ObjectDef def = serverData.object(obj.id());
        int w = def == null ? 1 : Math.max(1, def.width());
        int h = def == null ? 1 : Math.max(1, def.height());
        return Rect.footprint(obj.x(), obj.z(), w, h, obj.dir());
    }

    public MutableWorld world() {
        return world;
    }

    private long engageSentTick = Long.MIN_VALUE / 2;

    @Override
    public void noteEngageSent(long sessionTick) {
        this.engageSentTick = sessionTick;
    }

    protected GameState assemble() {
        Worn body = worn();
        Inventory inv = carriedInventory();
        Bank bk = bank.snapshot();
        Stats st = stats.snapshot();
        List<Npc> npcsNow = world.npcs();
        List<Npc> ghosts = world.npcGhosts();
        return new GameState(new GameState.Fields(
                serverData,
                sessionTick,
                serverTickNanos,
                serverTickTime,
                pos,
                username,
                selfServerIndex,
                inv,
                body,
                bk,
                holdings(),
                st,
                quests.snapshot(),
                questTableKnown,
                bankObserved,
                bankOpen,
                bankCloses,
                bankOps.snapshot(),
                dialog.snapshot(),
                messages.snapshot(),
                shop.snapshot(),
                auction.snapshot(),
                trade.snapshot(),
                fatigue.snapshot(),
                hud.snapshot(),
                duel.snapshot(),
                clan.snapshot(),
                party.snapshot(),
                selfPanel.snapshot(),
                social.snapshot(),
                features.snapshot(),
                region.snapshot(),
                npcsNow,
                ghosts,
                world.players(),
                playersWhole,
                new Scenery(world.scenery().freeze(), served, statics(), null),
                new Walls(world.walls().freeze(), served, statics(), null),
                world.groundItems(),
                tickEvents.snapshot(),
                NpcHazard.build(
                        pos,
                        npcsNow,
                        ghosts,
                        st.combatLevel(),
                        serverData.world().rules().npcBlocking()),
                wallOverlay.snapshot(),
                losOverlay.snapshot(),
                served,
                nav,
                engageSentTick,
                deaths,
                deathTile,
                hitsThisFight,
                attackSentTick,
                attackTargetIndex,
                Map.copyOf(attackSentTickByIndex),
                walkSent,
                castSentTick,
                castSpellId,
                castTargetIndex,
                itemCommandSentTick,
                itemCommandItemId,
                styleSent,
                describedLastTick,
                selfActionBubbleTick,
                selfActionBubbleItemId,
                Set.copyOf(prayersSentOn),
                selfMeleeOpponent(),
                rangeArmTick,
                rangeKillTick,
                rangeTargetIndex,
                selfRetreatTick,
                poisonDamage,
                lastPoisonHitTick,
                sprite,
                lastSelfChat,
                lastSelfChatTick,
                inAppearanceScreen,
                appearanceEditorOpen,
                onTutorial));
    }

    public void noteKillCredit(NpcKillCredit credit) {
        tickEvents().killCredit(credit);
    }

    public void noteSignal(Opcode opcode) {
        tickEvents().signal(opcode);
    }

    public void noteSelfDamage(int amount) {
        tickEvents().selfDamage(amount);
    }

    public void noteNpcDamage(int npcServerIndex, int damage) {
        tickEvents().npcDamage(npcServerIndex, damage);
    }

    public int planeIndex() {
        return region().snapshot().planeIndex();
    }

    public void setEquipment(List<WornItem> items) {
        selfPanel().equipment(items);
    }

    public void setEquipmentSlot(int wieldPos, int catalogId, int amount) {
        selfPanel().equipmentSlot(wieldPos, catalogId, amount);
    }

    public void setEquipmentStats(int[] stats) {
        selfPanel().equipmentStats(stats);
    }

    public void setPrayersActive(int[] flags) {
        selfPanel().prayersActive(flags);
    }

    public void setCombatStyle(int style) {
        hud().combatStyle(style);
    }

    public void updateFriend(FriendEntry entry) {
        social().friendUpdated(entry);
        tickEvents.friendUpdate(entry);
    }

    public void setIgnoreList(List<String> names) {
        social().ignoreList(names);
    }

    public void notePrivateMessage(PrivateMessage pm) {
        tickEvents().privateMessage(pm);
    }

    public void noteMessageBox(String text, boolean top) {
        tickEvents().messageBox(text, top);
        if (auctionHouse) {
            bankSilently(auction.said(text, sessionTick));
        }
    }

    public List<MessageBox> messageBoxesThisTick() {
        return tickEvents().snapshot().messageBoxes();
    }

    public boolean wantBankNotes() {
        return hud().snapshot().wantBankNotes();
    }

    public void setWantBankNotes(boolean want) {
        hud().wantBankNotes(want);
    }

    public WireLayout wireLayout() {
        Hud h = hud().snapshot();
        return new WireLayout(h.wantBankNotes(), h.wantDropX());
    }

    public void setWireFlags(PreLoginConfigs sent) {
        hud().wantBankNotes(sent.wantBankNotes());
        hud().wantDropX(sent.wantDropX());
        hud().serverConfigs(sent);
    }

    public void observeServerConfigs(PreLoginConfigs sent) {
        setWireFlags(sent);
        ServerRulesCheck.verify(serverData.world(), sent);
    }

    public void noteKillAnnouncement(KillAnnouncement k) {
        features().killAnnouncement(k);
        tickEvents.killAnnouncement(k);
    }

    public void setOnBlackHole(boolean v) {
        features().onBlackHole(v);
    }

    public void setIronman(IronmanHud h) {
        features().ironman(h);
    }

    public void setTrawler(TrawlerHud h) {
        features().trawler(h);
    }

    public void setOnlineList(List<OnlinePlayer> list) {
        social().onlineList(list);
    }

    public void setOpenPkPoints(long p) {
        features().openPkPoints(p);
    }

    public void setOpenPkPointsToGpRatio(long r) {
        features().openPkPointsToGpRatio(r);
    }

    public void putBankPreset(BankPreset preset) {
        features().bankPreset(preset);
    }

    public void setUnlockedAppearances(UnlockedAppearances u) {
        selfPanel().unlockedAppearances(u);
    }

    public void addRenderEvent(RenderEvent event) {
        tickEvents().renderEvent(event);
    }

    public void noteActionBubbleEvent(int index, boolean isPlayer, int itemId) {
        tickEvents().actionBubble(index, isPlayer, itemId);
    }

    public void noteClearedChunk(int chunkX, int chunkZ) {
        tickEvents().clearedChunk(chunkX, chunkZ);
    }

    public void noteSceneryReplaced(int x, int z, int id, int dir) {
        tickEvents().sceneryReplaced(new SceneryReplacement(x, z, id, dir, serverTick));
    }

    public void notePlayerChat(int senderIndex, String text, int icon, int type, boolean muted, boolean tutorial) {
        tickEvents().playerChat(new PlayerChat(senderIndex, null, text, icon, type, muted, tutorial));
        MutablePlayer sender = playerByServerIndex(senderIndex);
        if (sender != null) {
            sender.noteChat(text, serverTick);
        }
    }

    public void setShopHeader(boolean generalStore, int sellModifier, int buyModifier, int stockSensitivity) {
        shop().header(generalStore, sellModifier, buyModifier, stockSensitivity);
    }

    public void setShopStock(int catalogId, int amount, int baseAmount) {
        shop().stockSet(catalogId, amount, baseAmount);
    }

    public void clearShopStock() {
        shop().cleared();
    }

    public void noteTradeRequestFrom(String username) {
        trade().requestFrom(username);
    }

    public void openTrade(int opponentServerIndex) {
        trade().opened(opponentServerIndex, serverTick);
    }

    public void setTradeOffers(List<InventorySlot> myOffer, List<InventorySlot> otherOffer) {
        trade().offers(myOffer, otherOffer);
    }

    /**
     * Whether the player has accepted the trade's first screen. The server only ever sends one to cancel it, so a
     * client that sends the accept records it here.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1745-L1754">ActionSender.sendOwnTradeAcceptUpdate</a>
     */
    public void setTradeAccepted(boolean v) {
        trade().accepted(v);
    }

    public void setTradeOtherAccepted(boolean v) {
        trade().otherAccepted(v);
    }

    @Override
    public void noteTradeAcceptSent() {
        trade().accepted(true);
    }

    @Override
    public void noteDuelStakeSent(List<TradeOfferPacket.Offer> offers) {
        duel.myStake(offers.stream()
                .map(o -> new Duel.StakeItem(o.itemId(), o.noted(), o.amount()))
                .toList());
    }

    @Override
    public void noteTradeOfferSent() {
        trade().offerSentByUs();
    }

    public void setTradeConfirmOpen(boolean v) {
        trade().confirmOpen(v);
    }

    public void setTradeConfirmScreen(String partner, List<InventorySlot> myOffer, List<InventorySlot> otherOffer) {
        trade().confirmScreen(partner, myOffer, otherOffer);
    }

    public void closeTrade() {
        trade().closed();
    }

    public int sleepingFatigueShort() {
        return fatigue().sleepingFatigueShort();
    }

    public boolean sleeping() {
        return fatigue().sleeping();
    }

    public int incorrectSleepWordCount() {
        return fatigue().snapshot().incorrectSleepWordCount();
    }

    public double fatiguePercent() {
        return fatigue().snapshot().percent();
    }

    public void setFatigue(int percentShort, int authenticShort) {
        fatigue().fatigue(percentShort, authenticShort);
    }

    public void setSleepingFatigue(int percentShort, int authenticShort) {
        fatigue().sleepingFatigue(percentShort, authenticShort);
    }

    public void setSleeping(boolean v) {
        fatigue().sleeping(v);
        if (!v) {
            features().pendingCaptcha(null, 0);
        }
    }

    public void noteIncorrectSleepWord() {
        fatigue().incorrectSleepWord();
    }

    public void resetIncorrectSleepWord() {
        fatigue().resetIncorrectSleepWord();
    }

    public byte[] pendingCaptcha() {
        return features.snapshot().pendingCaptcha();
    }

    public void setPendingCaptcha(byte[] buf, int tick) {
        features().pendingCaptcha(buf, tick);
    }

    public int pendingCaptchaTick() {
        return features.snapshot().pendingCaptchaTick();
    }

    public int lastSleepFatigueTick() {
        return fatigue().snapshot().lastSleepFatigueTick();
    }

    public void noteSleepFatiguePush(int tick) {
        fatigue().sleepFatiguePush(tick);
    }

    private int serverTick;

    private boolean tickPending;
    private boolean tickEventsOpen;
    private @Nullable Attackable opponentAtBoundary;

    private int sprite;

    private long serverMessageCount;
    private boolean trackCombatPairs = true;

    private int sleepWakeupAt;
    private long lastPositionJumpFrom;
    private long lastPositionJumpTo;
    private boolean positionJumpPending;
    private @Nullable GameState frozen;

    private final CombatPairTracker combatPairs = new CombatPairTracker();
    private final WallOverlay wallOverlay = new WallOverlay();
    private ChunkBox box = ChunkBox.NONE;

    private ChunkBox previousBox = ChunkBox.NONE;
    private boolean boxCleared;
    private ChunkBox served = ChunkBox.NONE;
    private boolean streamArrived;
    private boolean tailShort;
    private final int gridRadius;

    private boolean hasScriptConsumer = true;
    private Holdings holdings = Holdings.EMPTY;
    private static final long SHOP_KEEPER_CHAT_WINDOW_TICKS = 50;
    private ShopNpc lastEngagedNpc;

    public static final int DEATH_DESPAWN_MAX_DIST_SQ = 212;

    public SessionKey sessionKey() {
        return sessionTick.session();
    }

    public SessionTick sessionTick() {
        return sessionTick;
    }

    public int serverTick() {
        return serverTick;
    }

    public int x() {
        return pos.x();
    }

    public int z() {
        return pos.y();
    }

    public int sprite() {
        return sprite;
    }

    public boolean inCombat() {
        return sprite == 8 || sprite == 9;
    }

    public int selfServerIndex() {
        return selfServerIndex;
    }

    public void noteSelfChat(String text, int icon, int type, boolean muted, boolean tutorial) {
        tickEvents.selfChat(new PlayerChat(selfServerIndex, username, text, icon, type, muted, tutorial));
        this.lastSelfChat = text;
        this.lastSelfChatTick = serverTick;
    }

    public int lastSelfActionBubbleTick() {
        return selfActionBubbleTick;
    }

    public int lastSelfActionBubbleItemId() {
        return selfActionBubbleItemId;
    }

    public void noteSelfActionBubble(int itemId) {
        this.selfActionBubbleTick = serverTick;
        this.selfActionBubbleItemId = itemId;
    }

    public void setAppearanceScreen(boolean v) {
        this.inAppearanceScreen |= v;
        this.appearanceEditorOpen = v;
    }

    public void setOnTutorial(boolean v) {
        this.onTutorial = v;
    }

    public boolean bankScreenOpen() {
        return bankOpen;
    }

    public boolean bankScreenFollowsEveryUpdate() {
        return serverData.world().rules().customBanks();
    }

    public void setBankScreenOpen(boolean v) {
        this.bankOpen = v;
        if (!v) {
            bankCloses++;
            bankOps.closed();
        }
    }

    public MutableBankOps bankOps() {
        return bankOps;
    }

    public long deathsObserved() {
        return deaths;
    }

    public void noteDeath() {
        deaths++;
        deathTile = pos;
    }

    public int lastTalkAttemptNpcIndex() {
        return dialog.lastTalkAttemptNpcIndex();
    }

    public int lastTalkAttemptTick() {
        return dialog.lastTalkAttemptTick();
    }

    public List<String> dialogueOptions() {
        return dialog.options();
    }

    public int dialogueMenuOrdinal() {
        return dialog.menuOrdinal();
    }

    public void noteDialogActivity() {
        dialog.activity(serverTick);
    }

    public void noteDialogResolved() {
        dialog.resolved();
    }

    public void noteNpcBusyReply(int npcServerIndex) {
        dialog.npcBusyReply(npcServerIndex, serverTick);
    }

    public NpcDialog npcDialog(int npcServerIndex) {
        return dialog.npcDialog(npcServerIndex);
    }

    public void noteNpcChat(int npcServerIndex, int recipientIndex, String message) {
        boolean toUs = recipientIndex == selfServerIndex;
        tickEvents.npcChat(npcServerIndex, recipientIndex, message);
        dialog.npcChatted(npcServerIndex, recipientIndex, toUs, message, serverTick);
    }

    // Every ProjectileFailureReason stops the RangeEvent that sent it:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/ProjectileFailureReason.java#L5
    private static final List<String> RANGE_FAILURES = List.of(
            "i've run out of ammo",
            "too powerful for your",
            "you don't have enough ammo in your",
            "will not fit",
            "you can't fire arrows with a crossbow",
            "you can't fire bolts with a bow",
            "i can't get close enough",
            "i can't get a clear shot",
            "you don't have any ammo equipped");

    private static boolean endsRangedAttack(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String f : RANGE_FAILURES) {
            if (lower.startsWith(f)) {
                return true;
            }
        }
        return false;
    }

    public void noteServerMessage(ServerMessage line) {
        String msg = line.text();

        messages.messageReceived(line, serverTick);
        this.serverMessageCount++;
        if (line.sender() == null && endsRangedAttack(msg)) {
            rangeKillTick = sessionTick().tick();
        }
        trade.serverMessage(msg);
        if (line.sender() == null) {
            bankOps.serverLine(msg);
            if (auctionHouse) {
                bankSilently(auction.said(msg, sessionTick));
            }
        }
    }

    public long serverMessageCount() {
        return serverMessageCount;
    }

    public List<String> serverMessagesThisTick() {
        return messages.snapshot().thisTickText();
    }

    public List<NpcChat> pendingNpcChat() {
        return tickEvents.openNpcChat();
    }

    public List<NpcKillCredit> pendingKillCredits() {
        return tickEvents.openKillCredits();
    }

    public void setHasScriptConsumer(boolean v) {
        this.hasScriptConsumer = v;
    }

    public boolean hasScriptConsumer() {
        return hasScriptConsumer;
    }

    public void setTrackCombatPairs(boolean v) {
        this.trackCombatPairs = v;
    }

    public int sleepWakeupAt() {
        return sleepWakeupAt;
    }

    public boolean consumeTickPending() {
        boolean was = tickPending;
        tickPending = false;
        return was;
    }

    public int tailDue() {
        var map = serverData.collisionMap();
        if (!hasPosition() || map == null) {
            return -1;
        }
        if (objects().isEmpty() && wallObjects().isEmpty() && groundItems().isEmpty()) {
            return -1;
        }
        return TailDue.terminator(
                pos.x(),
                pos.y(),
                gridRadius,
                (cx, cz) -> box.inLiveRadius(cx, cz) && !previousBox.inLiveRadius(cx, cz),
                objects(),
                wallObjects(),
                groundItems(),
                map.staticLocIndex());
    }

    public WallOverlay wallOverlay() {
        return wallOverlay;
    }

    public WallOverlay losOverlay() {
        return losOverlay;
    }

    private void serve() {
        ChunkBox next = !streamArrived ? box.dormant() : tailShort ? box.withoutEntering(previousBox) : box;
        served = next;
        wallOverlay.moveTo(next);
        losOverlay.moveTo(next);
    }

    public void tailCutShort() {
        tailShort = true;
        serve();
    }

    void noteStream() {
        if (!streamArrived) {
            streamArrived = true;
            serve();
        }
    }

    public void noteLiveView() {
        if (!hasPosition()) {
            return;
        }
        previousBox = box;
        if (boxCleared || !box.centredOn(pos.x() >> 3, pos.y() >> 3, gridRadius)) {
            box = ChunkBox.around(pos.x(), pos.y(), gridRadius);
            boxCleared = false;
        }
        tailShort = false;
        serve();
    }

    public boolean inView(int ax, int az) {
        return served.isLive(ax, az);
    }

    public GameState frozen() {
        GameState frozen = this.frozen;
        if (frozen == null) {
            throw new IllegalStateException("no tick frozen yet — the first coords packet has not landed");
        }
        return frozen;
    }

    @Override
    public void noteDialogActivity(int tick) {
        dialog.activity(tick);
    }

    @Override
    public void noteTalkAttempt(int npcServerIndex, int tick) {
        dialog.talkAttempt(npcServerIndex, tick);
    }

    private void bankSilently(AuctionHouse.@Nullable Banked banked) {
        if (banked != null) {
            bank.added(banked.itemId(), banked.amount());
        }
    }

    public void notePacketSent(Packet packet, SessionTick now) {
        packet.noteSent(this, now);
        auction.sent(packet, now, serverTickTime);
        bankOps.sent(packet, now, bankOpen);
    }

    public void seedBank(Bank known) {
        bank.setCapacity(known.capacity());
        bank.setAll(known.slots());
    }

    /**
     * Starts from the stages {@code known} has until the server's quest table replaces them, so the
     * ticks before it arrives read the account's quests, not none. {@link #questTableKnown} stays false.
     */
    public void seedQuests(Quests known) {
        for (IntIterator it = known.questIds().iterator(); it.hasNext(); ) {
            int id = it.nextInt();
            quests.setStage(id, known.stage(id));
        }
    }

    public void markBankObserved() {
        setBankObserved(true);
    }

    public Holdings holdings() {
        Inventory inv = carriedInventory();
        Bank bk = bank.snapshot();
        Holdings cached = holdings;
        if (cached.inventory() != inv || cached.bank() != bk) {
            cached = new Holdings(inv, bk);
            holdings = cached;
        }
        return cached;
    }

    public boolean questFinished(int questId) {
        return quests.snapshot().complete(questId);
    }

    public long carried(int itemId) {
        return carriedInventory().count(itemId);
    }

    private Inventory carriedPack = Inventory.empty();
    private List<WornItem> carriedEquipment = List.of();
    private Inventory carried = Inventory.empty();
    private @Nullable Worn wornView;

    public Inventory carriedInventory() {
        Inventory pack = inventory.snapshot();
        List<WornItem> eq = equipmentTab() ? selfPanel().snapshot().equipment() : List.of();
        if (pack != carriedPack || eq != carriedEquipment) {
            carriedPack = pack;
            carriedEquipment = eq;
            carried = Worn.carried(serverData, pack, eq);
            wornView = null;
        }
        return carried;
    }

    public Worn worn() {
        carriedInventory();
        Worn w = wornView;
        if (w == null) {
            w = Worn.of(serverData, carriedPack, carriedEquipment);
            wornView = w;
        }
        return w;
    }

    private boolean equipmentTab() {
        return serverData.world().rules().equipmentTab();
    }

    public long carriedLastTick(int itemId) {
        GameState last = frozen;
        return last == null ? carried(itemId) : last.inventory().count(itemId);
    }

    public int questStage(int questId) {
        return quests.snapshot().stage(questId);
    }

    public void setQuestStage(int questId, int stage) {
        quests.setStage(questId, stage);
    }

    public void noteQuestStageUpdate(int questId) {
        questStagesUpdated.set(questId);
    }

    public boolean questStageUpdatedThisTick(int questId) {
        return questId >= 0 && questStagesUpdated.get(questId);
    }

    public void markQuestTableReceived() {
        questTableKnown = true;
        questTableFresh = true;
    }

    public boolean questTableArrivedThisTick() {
        return questTableFresh;
    }

    public void questTablesFromSnapshot(boolean snapshot) {
        questTablesFromSnapshot = snapshot;
    }

    public boolean authoritativeQuestTableArrivedThisTick() {
        return questTableFresh && !questTablesFromSnapshot;
    }

    public int latchTick() {
        return serverTick;
    }

    public boolean questTableKnown() {
        return questTableKnown;
    }

    public void setQuestPoints(int points) {
        quests.setQuestPoints(points);
    }

    public void setInventory(List<InventorySlot> slots) {
        inventory.setAll(slots);
    }

    public void setInventorySlot(int slot, InventorySlot item) {
        inventory.setSlot(slot, item);
    }

    public void removeInventorySlot(int slot) {
        inventory.removeSlot(slot);
    }

    public void setAllSkills(int[] currentLevels, int[] maxLevels, long[] xps) {
        stats.setAll(currentLevels, maxLevels, xps);
    }

    public void setSkill(int id, int current, int max, long xp) {
        stats.setSkill(id, current, max, xp);
    }

    public void setHits(int current, int max) {
        stats.setLevels(Skill.HITS.id, current, max);
    }

    public void setSkillXp(int id, long xp) {
        stats.setXp(id, xp);
    }

    public void setServerSkillCount(int count) {
        stats.setServerSkillCount(count);
    }

    public void setShopScreenOpen(boolean v) {
        if (!v) {
            shop.closed();
            return;
        }
        if (shop.open()) {
            return;
        }
        ShopNpc fromServer = deriveShopKeeperFromServerChat();
        if (fromServer != null) {
            shop.opened(fromServer, true);
        } else {
            shop.opened(lastEngagedNpc, false);
        }
    }

    private ShopNpc deriveShopKeeperFromServerChat() {
        int chatIdx = dialog.chatToUsNpcIndex();
        if (chatIdx < 0 || serverTick - dialog.chatToUsTick() > SHOP_KEEPER_CHAT_WINDOW_TICKS) {
            return null;
        }
        Npc npc = world.npcByServerIndex(chatIdx);
        if (npc == null) {
            return null;
        }
        return new ShopNpc(chatIdx, npc.id(), npc.x(), npc.z(), serverTick);
    }

    public void setLoadArea(int serverIndex, int worldX, int worldZ, int planeIndex, int planeMultiplier) {
        this.selfServerIndex = serverIndex;
        region.loadArea(worldX, worldZ, planeIndex, planeMultiplier);
    }

    public void setSelfCoords(int rawX, int rawZ, int spriteNow) {
        region.coords(rawX, rawZ);
        if (hasPosition() && (Math.abs(rawX - pos.x()) > 8 || Math.abs(rawZ - pos.y()) > 8)) {
            this.lastPositionJumpFrom = (((long) pos.x()) << 32) | (pos.y() & 0xFFFFFFFFL);
            this.lastPositionJumpTo = (((long) rawX) << 32) | (rawZ & 0xFFFFFFFFL);
            this.positionJumpPending = true;
        }
        this.sprite = spriteNow;
        this.pos = Point.of(rawX, rawZ);
        noteLiveView();
    }

    public boolean consumePositionJump() {
        boolean was = positionJumpPending;
        positionJumpPending = false;
        return was;
    }

    public int lastJumpFromX() {
        return (int) (lastPositionJumpFrom >> 32);
    }

    public int lastJumpFromZ() {
        return (int) lastPositionJumpFrom;
    }

    public int lastJumpToX() {
        return (int) (lastPositionJumpTo >> 32);
    }

    public int lastJumpToZ() {
        return (int) lastPositionJumpTo;
    }

    public List<MutableNpc> snapshotNpcsInView() {
        List<MutableNpc> out = new ArrayList<>(world.liveNpcs().size());
        for (Npc n : world.liveNpcs()) {
            out.add((MutableNpc) n);
        }
        return out;
    }

    public void beginNpcTick() {
        world.beginNpcs();
    }

    public void appendNpcInView(MutableNpc n) {
        world.addNpc(n);
    }

    public void despawnNpc(MutableNpc n) {
        int idx = n.serverIndex();
        if (idx == rangeTargetIndex && rangeKillTick < rangeArmTick) {
            // A ranged attack stops once its target is down:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/RangeEvent.java#L77
            rangeKillTick = sessionTick().tick();
        }
        world.removeNpc(n);
        dialog.npcRemoved(idx);
        int dx = n.x() - pos.x();
        int dz = n.z() - pos.y();
        if (dx * dx + dz * dz <= DEATH_DESPAWN_MAX_DIST_SQ) {
            tickEvents.npcDeath(new NpcDeath(idx, n.id(), n.x(), n.z()));
        } else if ((n.def() != null && n.def().aggressive())
                || serverData.world().rules().npcBlocking().mayBlock(n.def())) {
            world.addNpcGhost(n);
        }
    }

    public MutableNpc spawnNpc(int serverIndex, int id, int nx, int nz, int npcSprite) {
        NpcDef def = serverData.npc(id);
        ServerData.NpcSpawn spawn = serverData.npcSpawnOf(serverIndex, id, nx, nz);
        MutableNpc n = new MutableNpc(serverIndex, id, nx, nz, npcSprite, def, spawn);
        world.indexNpc(n);
        world.removeNpcGhost(serverIndex);
        world.addNpc(n);
        return n;
    }

    public MutableNpc npcByServerIndex(int idx) {
        return (MutableNpc) world.npcByServerIndex(idx);
    }

    public List<Npc> npcsInViewSnapshot() {
        return world.npcs();
    }

    public void publishNpcView() {
        for (Npc ghost : world.npcGhosts()) {
            int gx = ghost.x() - pos.x();
            int gz = ghost.z() - pos.y();
            if (gx * gx + gz * gz <= DEATH_DESPAWN_MAX_DIST_SQ) {
                world.removeNpcGhost(ghost.serverIndex());
            }
        }
    }

    public List<MutablePlayer> snapshotPlayersInView() {
        List<MutablePlayer> out = new ArrayList<>(world.livePlayers().size());
        for (Player p : world.livePlayers()) {
            out.add((MutablePlayer) p);
        }
        return out;
    }

    public void beginPlayerTick() {
        world.beginPlayers();
    }

    private boolean playersWhole = true;

    public void notePlayersUnplaced(int unplaced) {
        playersWhole = unplaced == 0;
    }

    public void appendPlayerInView(MutablePlayer p) {
        world.addPlayer(p);
    }

    public void despawnPlayer(MutablePlayer p) {
        p.clearTrail();
        world.removePlayer(p);
    }

    public MutablePlayer spawnPlayer(int serverIndex, int px, int pz, int playerSprite) {
        MutablePlayer p = new MutablePlayer(serverIndex, px, pz, playerSprite);
        world.indexPlayer(p);
        world.addPlayer(p);
        return p;
    }

    public MutablePlayer playerByServerIndex(int idx) {
        return (MutablePlayer) world.playerByServerIndex(idx);
    }

    public void publishPlayerView() {}

    public Attackable selfMeleeOpponent() {
        return frozenMob(combatPairs.opponentOfSelf());
    }

    public Attackable meleeOpponentOf(Attackable mob) {
        Object live = mob instanceof Npc n
                ? world.npcByServerIndex(n.serverIndex())
                : mob instanceof Player p ? world.playerByServerIndex(p.serverIndex()) : mob;
        return live instanceof Attackable a ? frozenMob(combatPairs.opponentOf(a)) : null;
    }

    private static Attackable frozenMob(Attackable mob) {
        if (mob instanceof MutableNpc n) {
            return n.snapshot();
        }
        if (mob instanceof MutablePlayer p) {
            return p.snapshot();
        }
        return mob;
    }

    public GameObject updateObject(int id, int ox, int oz, int dir) {
        noteStream();
        return world.updateObject(id, ox, oz, dir);
    }

    public void updateWallObject(int id, int wx, int wz, int dir) {
        noteStream();
        world.updateWallObject(id, wx, wz, dir);
    }

    public Collection<WallObject> wallObjects() {
        return world.walls().all();
    }

    public Collection<GameObject> objects() {
        return world.scenery().all();
    }

    public GameObject objectAt(int ox, int oz) {
        return world.scenery().at(ox, oz);
    }

    public void addGroundItem(int id, int gx, int gz, boolean noted) {
        world.addGroundItem(id, gx, gz, noted);
    }

    public boolean removeGroundItem(int id, int gx, int gz) {
        return world.removeGroundItem(id, gx, gz);
    }

    public void clearGroundItemsAt(int gx, int gz) {
        world.clearGroundItemsAt(gx, gz);
    }

    public int clearChunk(int chunkX, int chunkZ) {
        int removed = world.clearChunk(chunkX, chunkZ);
        ChunkBox cleared = box.withoutChunk(chunkX, chunkZ);
        if (cleared != box) {
            box = cleared;
            boxCleared = true;
            serve();
        }
        wallOverlay.clearChunk(chunkX, chunkZ);
        losOverlay.clearChunk(chunkX, chunkZ);
        return removed;
    }

    public List<GroundItem> groundItems() {
        return world.groundItems().stream().toList();
    }

    public void noteTickBoundary() {
        noteTickBoundary(serverTick + 1, Instant.ofEpochMilli(clockMs.getAsLong()));
    }

    public Instant serverTickTime() {
        return serverTickTime;
    }

    /**
     * Times tick boundaries by {@code ms}, in epoch milliseconds, instead of the wall clock: both
     * {@link #serverTickTime} and {@link GameState#serverTickNanos} then read it, so states on one
     * clock compare.
     */
    public void clock(java.util.function.LongSupplier ms) {
        clockMs = Objects.requireNonNull(ms, "ms");
        clockNanos = () -> java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ms.getAsLong());
    }

    public void noteTickBoundary(int tick, Instant at) {
        if (tickEventsOpen) {
            publishTickEvents();
        }
        serverTick = tick;
        serverTickTime = at;
        serverTickNanos = clockNanos.getAsLong();
        tickPending = true;
        tickEventsOpen = true;
        opponentAtBoundary = trackCombatPairs ? combatPairs.opponentOfSelf() : null;
        if (trackCombatPairs) {
            combatPairs.onTickBoundary(world.liveNpcs(), world.livePlayers(), pos.x(), pos.y(), sprite);
        }
        for (Npc npc : world.liveNpcs()) {
            if (npc instanceof MutableNpc n) {
                if (n.inCombat()) {
                    if (n.combatSinceTick() < 0) {
                        n.setCombatSinceTick(serverTick);
                    }
                } else if (n.combatSinceTick() >= 0) {
                    n.setCombatSinceTick(-1);
                }
            }
        }
        trade.bundlePublished(serverTick);
        messages.bundlePublished();
    }

    private void publishTickEvents() {
        tickEventsOpen = false;
        tickEvents.bundlePublished(idx -> {
            Player p = world.playerByServerIndex(idx);
            return p == null ? null : p.username();
        });
        boolean theyFled = false;
        for (ServerMessage sm : messages.thisTick()) {
            if (sm.text().toLowerCase(Locale.ROOT).contains("your opponent is retreating")) {
                theyFled = true;
                break;
            }
        }
        boolean weFled = false;
        if (!theyFled) {
            for (RenderEvent ev : tickEvents.snapshot().renderEvents()) {
                if (ev instanceof RenderEvent.Sound sound && RETREAT_SOUND.equals(sound.name())) {
                    selfRetreatTick = serverTick;
                    weFled = true;
                    break;
                }
            }
        }
        if (theyFled || weFled) {
            noteOpponentRetreat(opponentAtBoundary, serverTick);
        }
    }

    private static final String RETREAT_SOUND = "retreat";

    private void noteOpponentRetreat(@Nullable Attackable preBoundaryOpponent, int serverTick) {
        if (preBoundaryOpponent instanceof MutablePlayer fledPlayer) {
            fledPlayer.noteRetreat(serverTick);
            return;
        }
        Npc fledNpc = preBoundaryOpponent instanceof Npc n ? n : frozen == null ? null : frozen.npcOpponent();
        if (fledNpc != null) {
            MutableNpc live = npcByServerIndex(fledNpc.serverIndex());
            if (live != null) {
                live.noteRetreat(serverTick);
            }
        }
    }

    public GameState freezeTick() {
        if (!hasPosition()) {
            throw new IllegalStateException(
                    "freezing before the first coords packet — a GameState always has a position");
        }
        if (tickEventsOpen) {
            publishTickEvents();
        }
        this.sessionTick = new SessionTick(sessionTick.session(), serverTick);
        questTableFresh = false;
        questStagesUpdated.clear();
        noteCombatTick();
        GameState frozen = assemble();
        this.frozen = frozen;
        return frozen;
    }
}
