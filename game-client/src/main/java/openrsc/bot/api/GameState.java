package openrsc.bot.api;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Holdings;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.Location;
import openrsc.gamedata.data.RangeWeapons;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.LocalOverlay;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.Planner;
import org.jetbrains.annotations.Nullable;

/**
 * One session's view of the game as of one server tick: everything the server has told it, plus what
 * it sent. Frozen by {@link MutableGameState#freezeTick}; nothing in it changes afterwards.
 */
public class GameState implements World, Overlays, AccountFacts {

    record Fields(
            ServerData serverData,
            SessionTick sessionTick,
            long serverTickNanos,
            Instant serverTickTime,
            Point pos,
            String username,
            int selfServerIndex,
            Inventory inventory,
            Worn worn,
            Bank bank,
            Holdings owned,
            Stats stats,
            Quests quests,
            boolean questsKnown,
            boolean bankObserved,
            boolean bankOpen,
            long bankCloses,
            BankOpsOut bankOpsOut,
            Dialog dialog,
            Messages messages,
            Shop shop,
            AuctionHouse auction,
            TradeView trade,
            Fatigue fatigue,
            Hud hud,
            Duel duel,
            Clan clan,
            Party party,
            SelfPanel selfPanel,
            Social social,
            Features features,
            Region region,
            List<Npc> npcs,
            List<Npc> npcGhosts,
            List<Player> players,
            boolean playersWhole,
            Scenery scenery,
            Walls walls,
            GroundItems groundItems,
            TickEvents tickEvents,
            NpcHazard npcHazard,
            LocalOverlay walkOverlay,
            LocalOverlay losOverlay,
            ChunkBox liveBox,
            @Nullable Planner nav,
            long engageSentTick,
            long deaths,
            @Nullable Point deathTile,
            int hitsThisFight,
            @Nullable SessionTick attackSentTick,
            int attackTargetIndex,
            Map<Integer, Integer> attackSentTickByIndex,
            @Nullable SentWalk walkSent,
            @Nullable SessionTick castSentTick,
            int castSpellId,
            int castTargetIndex,
            @Nullable SessionTick itemCommandSentTick,
            int itemCommandItemId,
            int styleSent,
            List<String> describedLastTick,
            int selfActionBubbleTick,
            int selfActionBubbleItemId,
            Set<Integer> prayersSentOn,
            @Nullable Attackable meleeOpponent,
            int rangeArmTick,
            int rangeKillTick,
            int rangeTargetIndex,
            int selfRetreatTick,
            int poisonDamage,
            @Nullable SessionTick lastPoisonHitTick,
            int spriteDir,
            @Nullable String lastSelfChat,
            int lastSelfChatTick,
            boolean inAppearanceScreen,
            boolean appearanceEditorOpen,
            boolean onTutorial) {}

    private final Fields f;

    GameState(Fields f) {
        this.f = f;
    }

    protected GameState(GameState of) {
        this.f = of.f;
    }

    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + username() + " @" + sessionTick() + " (" + pos().x() + "," + pos().y()
                + ")}";
    }

    @Override
    public boolean online() {
        return true;
    }

    @Override
    public List<String> groups() {
        return List.of(DEFAULT_GROUP);
    }

    @Override
    public CacheValues cacheValues() {
        return CacheValues.EMPTY;
    }

    @Override
    public Scenery scenery() {
        return f.scenery().boundTo(this);
    }

    @Override
    public Walls walls() {
        return f.walls().boundTo(this);
    }

    @Override
    public GroundItems groundItems() {
        return f.groundItems().boundTo(this);
    }

    @Override
    public ServerData serverData() {
        return f.serverData();
    }

    public SessionTick sessionTick() {
        return f.sessionTick();
    }

    public long serverTickNanos() {
        return f.serverTickNanos();
    }

    public Instant serverTickTime() {
        return f.serverTickTime();
    }

    @Override
    public Point pos() {
        return f.pos();
    }

    @Override
    public String username() {
        return f.username();
    }

    public int selfServerIndex() {
        return f.selfServerIndex();
    }

    @Override
    public Inventory inventory() {
        return f.inventory();
    }

    public Worn worn() {
        return f.worn();
    }

    @Override
    public Bank bank() {
        return f.bank();
    }

    @Override
    public Holdings owned() {
        return f.owned();
    }

    @Override
    public Stats stats() {
        return f.stats();
    }

    @Override
    public Quests quests() {
        return f.quests();
    }

    public boolean questsKnown() {
        return f.questsKnown();
    }

    public boolean bankObserved() {
        return f.bankObserved();
    }

    public boolean bankOpen() {
        return f.bankOpen();
    }

    public long bankCloses() {
        return f.bankCloses();
    }

    public BankOpsOut bankOpsOut() {
        return f.bankOpsOut();
    }

    public Dialog dialog() {
        return f.dialog();
    }

    public Messages messages() {
        return f.messages();
    }

    public Shop shop() {
        return f.shop();
    }

    public AuctionHouse auction() {
        return f.auction();
    }

    public TradeView trade() {
        return f.trade();
    }

    public Fatigue fatigue() {
        return f.fatigue();
    }

    public Hud hud() {
        return f.hud();
    }

    public Duel duel() {
        return f.duel();
    }

    public Clan clan() {
        return f.clan();
    }

    public Party party() {
        return f.party();
    }

    public SelfPanel selfPanel() {
        return f.selfPanel();
    }

    public Social social() {
        return f.social();
    }

    public Features features() {
        return f.features();
    }

    public Region region() {
        return f.region();
    }

    @Override
    public List<Npc> npcs() {
        return f.npcs();
    }

    @Override
    public List<Npc> npcGhosts() {
        return f.npcGhosts();
    }

    @Override
    public List<Player> players() {
        return f.players();
    }

    public boolean playersWhole() {
        return f.playersWhole();
    }

    public TickEvents tickEvents() {
        return f.tickEvents();
    }

    public NpcHazard npcHazard() {
        return f.npcHazard();
    }

    @Override
    public LocalOverlay walkOverlay() {
        return f.walkOverlay();
    }

    @Override
    public LocalOverlay losOverlay() {
        return f.losOverlay();
    }

    public ChunkBox liveBox() {
        return f.liveBox();
    }

    @Override
    public Planner nav() {
        Planner nav = f.nav();
        if (nav == null) {
            throw new IllegalStateException("this game state was built without a planner");
        }
        return nav;
    }

    public long engageSentTick() {
        return f.engageSentTick();
    }

    public long deaths() {
        return f.deaths();
    }

    public @Nullable Point deathTile() {
        return f.deathTile();
    }

    public int hitsThisFight() {
        return f.hitsThisFight();
    }

    public @Nullable SessionTick attackSentTick() {
        return f.attackSentTick();
    }

    public int attackTargetIndex() {
        return f.attackTargetIndex();
    }

    public Map<Integer, Integer> attackSentTickByIndex() {
        return f.attackSentTickByIndex();
    }

    public @Nullable SentWalk walkSent() {
        return f.walkSent();
    }

    public @Nullable SessionTick castSentTick() {
        return f.castSentTick();
    }

    public int castSpellId() {
        return f.castSpellId();
    }

    public int castTargetIndex() {
        return f.castTargetIndex();
    }

    public @Nullable SessionTick itemCommandSentTick() {
        return f.itemCommandSentTick();
    }

    public int itemCommandItemId() {
        return f.itemCommandItemId();
    }

    public int styleSent() {
        return f.styleSent();
    }

    public List<String> describedLastTick() {
        return f.describedLastTick();
    }

    public int selfActionBubbleTick() {
        return f.selfActionBubbleTick();
    }

    public int selfActionBubbleItemId() {
        return f.selfActionBubbleItemId();
    }

    public Set<Integer> prayersSentOn() {
        return f.prayersSentOn();
    }

    public @Nullable Attackable meleeOpponent() {
        return f.meleeOpponent();
    }

    public int rangeArmTick() {
        return f.rangeArmTick();
    }

    public int rangeKillTick() {
        return f.rangeKillTick();
    }

    public int rangeTargetIndex() {
        return f.rangeTargetIndex();
    }

    public int selfRetreatTick() {
        return f.selfRetreatTick();
    }

    public int poisonDamage() {
        return f.poisonDamage();
    }

    public @Nullable SessionTick lastPoisonHitTick() {
        return f.lastPoisonHitTick();
    }

    public int spriteDir() {
        return f.spriteDir();
    }

    public @Nullable String lastSelfChat() {
        return f.lastSelfChat();
    }

    public int lastSelfChatTick() {
        return f.lastSelfChatTick();
    }

    public boolean inAppearanceScreen() {
        return f.inAppearanceScreen();
    }

    public boolean appearanceEditorOpen() {
        return f.appearanceEditorOpen();
    }

    public boolean onTutorial() {
        return f.onTutorial();
    }

    public @Nullable Npc npcByServerIndex(int serverIndex) {
        for (Npc n : npcs()) {
            if (n.serverIndex() == serverIndex) {
                return n;
            }
        }
        return null;
    }

    public @Nullable Player playerByServerIndex(int serverIndex) {
        for (Player p : players()) {
            if (p.serverIndex() == serverIndex) {
                return p;
            }
        }
        return null;
    }

    public @Nullable Player playerByUsername(@Nullable String username) {
        if (username == null) {
            return null;
        }
        for (Player p : players()) {
            if (username.equalsIgnoreCase(p.username())) {
                return p;
            }
        }
        return null;
    }

    public @Nullable Npc nearestStaff(Location loc) {
        Point pos = pos();
        Npc best = null;
        int bestD = Integer.MAX_VALUE;
        for (Npc n : npcs()) {
            if (loc.servedBy(spawnOf(n))) {
                int d = pickDistance(pos, n);
                if (d < bestD) {
                    bestD = d;
                    best = n;
                }
            }
        }
        return best;
    }

    public @Nullable ServerData.NpcSpawn spawnOf(Npc n) {
        return serverData().npcSpawnOf(n.serverIndex(), n.id(), n.x(), n.z());
    }

    public @Nullable Npc nearestNpc(int... npcIds) {
        Point pos = pos();
        Npc best = null;
        int bestD = Integer.MAX_VALUE;
        for (Npc n : npcs()) {
            for (int id : npcIds) {
                if (n.id() == id) {
                    int d = pickDistance(pos, n);
                    if (d < bestD) {
                        bestD = d;
                        best = n;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static int pickDistance(Point pos, Npc n) {
        return Point.chebyshev(pos.x(), pos.y(), n.x(), n.z()) + (n.desynced() ? DESYNCED_PICK_PENALTY : 0);
    }

    private static final int DESYNCED_PICK_PENALTY = 1 << 20;

    public boolean inCombat() {
        return spriteDir() == 8 || spriteDir() == 9;
    }

    public @Nullable Npc combatOpponent() {
        Point pos = pos();
        for (Npc n : npcs()) {
            if (n.inCombat() && n.x() == pos.x() && n.z() == pos.y()) {
                return n;
            }
        }
        return null;
    }

    public @Nullable Npc npcOpponent() {
        if (!inCombat()) {
            return null;
        }
        Attackable melee = meleeOpponent();
        return melee instanceof Npc m ? m : combatOpponent();
    }

    public record SentWalk(int tick, List<Point> waypoints) {}

    public int attackSentTick(int serverIndex) {
        Integer t = attackSentTickByIndex().get(serverIndex);
        return t == null ? -1 : t;
    }

    public boolean prayerSentOn(int prayerId) {
        return prayersSentOn().contains(prayerId);
    }

    public boolean rangeEventLive(int targetIndex) {
        if (targetIndex != rangeTargetIndex() || rangeArmTick() == Integer.MIN_VALUE) {
            return false;
        }
        if (rangeKillTick() >= rangeArmTick()) {
            return false;
        }
        for (String m : messages().thisTickText()) {
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("can't get close enough") || lower.contains("can't get a clear shot")) {
                return false;
            }
        }
        return true;
    }

    public boolean rangingNow() {
        return rangeArmTick() != Integer.MIN_VALUE && rangeKillTick() < rangeArmTick();
    }

    public boolean poisoned() {
        return poisonDamage() > 0;
    }

    public int ticksSince(@Nullable SessionTick earlier) {
        return sessionTick().elapsedSinceOr(earlier, Integer.MAX_VALUE);
    }

    public Inventory pack() {
        return worn().pack();
    }

    public boolean inView(int x, int z) {
        return liveBox().isLive(x, z);
    }

    public boolean checkPath(int fx, int fz, int tx, int tz) {
        return map().checkPath(fx, fz, tx, tz, this);
    }

    public boolean checkPath(int tx, int tz) {
        return checkPath(pos().x(), pos().y(), tx, tz);
    }

    @Override
    public boolean canStep(Point from, int tx, int ty) {
        return map().canStep(from.x(), from.y(), tx, ty, this);
    }

    public boolean canStep(int fx, int fz, int tx, int tz) {
        return canStep(Point.of(fx, fz), tx, tz);
    }

    public boolean fullyBlocked(Point tile) {
        return map().fullyBlocked(tile.x(), tile.y(), this);
    }

    public boolean canEngage(@Nullable Targetable target) {
        return canEngage(pos(), target);
    }

    @Override
    public boolean canEngage(Point from, @Nullable Targetable target) {
        return target != null && map().canEngage(from.x(), from.y(), target, this);
    }

    public List<Point> engageTiles(Targetable target) {
        return map().engageTiles(target, this);
    }

    public int projectileRadius() {
        WornItem weapon = worn().rangeWeapon();
        return weapon == null ? RangeWeapons.DEFAULT_PROJECTILE_RADIUS : RangeWeapons.radius(weapon.catalogId());
    }

    public boolean canCast(Targetable target) {
        Point p = pos();
        return map().resolvesFrom(p.x(), p.y(), target.x(), target.z(), CollisionMap.SPELL_RANGE, this);
    }

    public boolean canRange(Targetable target) {
        Point p = pos();
        return map().resolvesFrom(p.x(), p.y(), target.x(), target.z(), projectileRadius(), this);
    }

    public List<Point> rangeTiles(Targetable target, int range) {
        return map().rangeTiles(target.x(), target.z(), range, this);
    }

    public Rect footprintOf(GameObject obj) {
        ObjectDef def = serverData().object(obj.id());
        int w = def == null ? 1 : Math.max(1, def.width());
        int h = def == null ? 1 : Math.max(1, def.height());
        return Rect.footprint(obj.x(), obj.z(), w, h, obj.dir());
    }

    public boolean isClimbable(int sceneryId) {
        ObjectDef def = serverData().object(sceneryId);
        return def != null && def.isClimbable();
    }

    @Override
    public @Nullable Point closestEngageTile(Point from, Targetable target) {
        return map().closestEngageTile(from.x(), from.y(), target, this);
    }

    private CollisionMap map() {
        return serverData().collisionMap();
    }
}
