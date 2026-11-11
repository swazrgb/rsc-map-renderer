package openrsc.bot.core.world;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import openrsc.bot.api.Appearance;
import openrsc.bot.api.AuctionHouse;
import openrsc.bot.api.BankPreset;
import openrsc.bot.api.Clan;
import openrsc.bot.api.Duel;
import openrsc.bot.api.Features.IronmanHud;
import openrsc.bot.api.Features.KillAnnouncement;
import openrsc.bot.api.Features.TrawlerHud;
import openrsc.bot.api.FriendEntry;
import openrsc.bot.api.GameObject;
import openrsc.bot.api.MutableClan;
import openrsc.bot.api.MutableGameState;
import openrsc.bot.api.MutableNpc;
import openrsc.bot.api.MutableParty;
import openrsc.bot.api.MutablePlayer;
import openrsc.bot.api.NpcKillCredit;
import openrsc.bot.api.OnlinePlayer;
import openrsc.bot.api.Party;
import openrsc.bot.api.Player;
import openrsc.bot.api.PrivateMessage;
import openrsc.bot.api.ServerMessage;
import openrsc.bot.api.UnlockedAppearances;
import openrsc.bot.api.WornItem;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.core.codec.Huffman;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.bot.core.net.InboundEvent;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.WallOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

public final class PacketDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(PacketDispatcher.class);

    private static final AtomicBoolean CONFIGS_UNPARSEABLE_LOGGED = new AtomicBoolean();

    private Logger log = LOG;

    private boolean replay;

    private boolean legacyKeyframe;

    private Logger rxLog;

    private final String accountName;
    private final MutableGameState state;
    private final ItemDefs itemDefs;
    private final ObjectDefs objectDefs;
    private final DoorDefs doorDefs;

    public PacketDispatcher(
            String accountName, MutableGameState state, ItemDefs itemDefs, ObjectDefs objectDefs, DoorDefs doorDefs) {
        this.accountName = accountName;
        this.rxLog = LoggerFactory.getLogger("openrsc.bot.script." + accountName);
        this.state = state;
        this.itemDefs = itemDefs;
        this.objectDefs = objectDefs;
        this.doorDefs = doorDefs;
    }

    public static boolean beginsTickBundle(int opcode) {
        return opcode == Opcode.SEND_NPC_COORDS.wire();
    }

    public static boolean continuesTickBundle(int opcode) {
        return opcode == Opcode.SEND_UPDATE_NPC.wire()
                || opcode == Opcode.SEND_SCENERY.wire()
                || opcode == Opcode.SEND_WALL_OBJECTS.wire()
                || opcode == Opcode.SEND_GROUND_ITEMS.wire()
                || opcode == Opcode.SEND_REMOVE_WORLD_ENTITY.wire();
    }

    private static final Set<Opcode> SCAN_LITE_SKIP = EnumSet.of(
            Opcode.SEND_SCENERY,
            Opcode.SEND_WALL_OBJECTS,
            Opcode.SEND_GROUND_ITEMS,
            Opcode.SEND_INVENTORY,
            Opcode.SEND_INVENTORY_UPDATEITEM,
            Opcode.SEND_INVENTORY_REMOVEITEM);

    private boolean scanLite;

    private int previousWire = -1;

    private boolean playerViewDriftWarned;

    private boolean playerViewWrapWarned;

    private boolean playerViewUndecodableWarned;

    public void setScanLite(boolean v) {
        this.scanLite = v;
        state.setTrackCombatPairs(!v);
    }

    public void setReplay() {
        this.rxLog = NOPLogger.NOP_LOGGER;
        this.log = NOPLogger.NOP_LOGGER;
        this.replay = true;
    }

    public void setLegacyKeyframe(boolean legacy) {
        this.legacyKeyframe = legacy;
    }

    public void apply(InboundEvent.Packet p) {
        int wire = p.opcode();
        int previous = previousWire;
        previousWire = wire;
        Opcode op = Opcode.inbound(wire);
        ByteBuf body = p.payload();
        if (op == null) {
            log.debug("[{}] unhandled opcode {} ({} bytes)", accountName, wire, body.readableBytes());
            return;
        }
        if (scanLite) {
            if (op == Opcode.SEND_NPC_COORDS) {
                state.noteTickBoundary();
                return;
            }
            if (SCAN_LITE_SKIP.contains(op)) {
                return;
            }
        }
        switch (op) {
            case Opcode.SEND_LOAD_AREA -> handleLoadArea(body);
            case Opcode.SEND_PLAYER_COORDS -> handlePlayerCoords(body);
            case Opcode.SEND_NPC_COORDS -> handleNpcCoords(body);
            case Opcode.SEND_SCENERY -> handleScenery(body);
            case Opcode.SEND_WALL_OBJECTS -> handleWallObjects(body);
            case Opcode.SEND_INVENTORY -> {
                handleInventory(body);
                state.bankOps().packChanged();
            }
            case Opcode.SEND_INVENTORY_UPDATEITEM -> {
                handleInventoryUpdateItem(body);
                state.bankOps().packChanged();
            }
            case Opcode.SEND_INVENTORY_REMOVEITEM -> {
                handleInventoryRemoveItem(body);
                state.bankOps().packChanged();
            }
            case Opcode.SEND_SERVER_MESSAGE -> handleServerMessage(body);
            case Opcode.SEND_LOGOUT -> {
                state.noteSignal(Opcode.SEND_LOGOUT);
                log.info("[{}] server signalled logout", accountName);
            }
            case Opcode.SEND_DEATH -> {
                state.noteDeath();
                log.info(
                        "[{}] DIED (opcode 83) — respawn coords incoming next tick (#deaths={})",
                        accountName,
                        state.deathsObserved());
            }
            case Opcode.SEND_FATIGUE -> handleFatigue(body);
            case Opcode.SEND_SLEEP_FATIGUE -> handleSleepFatigue(body);
            case Opcode.SEND_SLEEPSCREEN -> handleSleepScreen(body);
            case Opcode.SEND_STOPSLEEP -> {
                state.setSleeping(false);
                state.resetIncorrectSleepWord();
                log.info("[{}] woke up (fatigue now {}%)", accountName, String.format("%.1f", state.fatiguePercent()));
            }
            case Opcode.SEND_SLEEPWORD_INCORRECT -> {
                state.noteIncorrectSleepWord();
                state.noteSignal(Opcode.SEND_SLEEPWORD_INCORRECT);
                log.warn("[{}] incorrect sleep word (#{} this session)", accountName, state.incorrectSleepWordCount());
            }
            case Opcode.SEND_STATS -> handleStats(body);
            case Opcode.SEND_STAT -> handleStat(body);
            case Opcode.SEND_EXPERIENCE -> handleExperience(body);
            case Opcode.SEND_BANK_OPEN -> {
                boolean opens = bankScreenOpens(previous);
                handleBankOpen(body, opens);
                if (opens) {
                    state.setBankScreenOpen(true);
                }
            }
            case Opcode.SEND_BANK_UPDATE -> handleBankUpdate(body);
            case Opcode.SEND_BANK_CLOSE -> state.setBankScreenOpen(false);
            case Opcode.SEND_SHOP_OPEN -> handleShopOpen(body);
            case Opcode.SEND_SHOP_CLOSE -> {
                state.setShopScreenOpen(false);
                log.info("[{}] shop closed", accountName);
            }
            case Opcode.SEND_APPEARANCE_SCREEN -> {
                state.setAppearanceScreen(true);
                log.info("[{}] appearance screen opened", accountName);
            }
            case Opcode.SEND_ON_TUTORIAL -> {
                boolean on = body.isReadable() && body.readUnsignedByte() != 0;
                state.setOnTutorial(on);
                log.info("[{}] tutorial state: {}", accountName, on);
            }
            case Opcode.SEND_GROUND_ITEMS -> handleGroundItems(body);
            case Opcode.SEND_QUESTS -> handleQuests(body);
            case Opcode.SEND_OPTION_MENU_OPEN -> {
                handleOptionMenuOpen(body);
                state.noteDialogActivity();
            }
            case Opcode.SEND_OPTION_MENU_CLOSE -> {
                state.dialog().menuClosed();
                rxLog.info("[{} t{}] IN menu-close", accountName, state.serverTick());
            }
            case Opcode.SEND_BOX, Opcode.SEND_BOX2 -> {
                state.noteDialogActivity();
                String raw = body.isReadable()
                        ? body.toString(body.readerIndex(), body.readableBytes(), StandardCharsets.UTF_8)
                        : "";
                body.skipBytes(body.readableBytes());
                state.noteMessageBox(raw, op == Opcode.SEND_BOX);
                rxLog.info(
                        "[{} t{}] IN message-box: '{}'",
                        accountName,
                        state.serverTick(),
                        raw.replace((char) 0x0A, ' ').trim());
            }
            case Opcode.SEND_NPC_KILLS -> handleNpcKills(body);
            case Opcode.SEND_UPDATE_NPC -> handleUpdateNpc(body);
            case Opcode.SEND_UPDATE_PLAYERS -> handleUpdatePlayers(body);
            case Opcode.SEND_REMOVE_WORLD_ENTITY -> handleRemoveWorldEntity(body);
            case Opcode.SEND_TRADE_WINDOW -> handleTradeWindow(body);
            case Opcode.SEND_TRADE_OTHER_ITEMS -> handleTradeItems(body);
            case Opcode.SEND_TRADE_ACCEPTED -> state.setTradeAccepted(readAcceptFlag(body));
            case Opcode.SEND_TRADE_OTHER_ACCEPTED -> state.setTradeOtherAccepted(readAcceptFlag(body));
            case Opcode.SEND_TRADE_OPEN_CONFIRM -> handleTradeConfirm(body);
            case Opcode.SEND_TRADE_CLOSE -> {
                state.closeTrade();
                log.debug("[{}] trade: window closed", accountName);
            }
            case Opcode.SEND_EQUIPMENT -> {
                handleEquipment(body);
                state.bankOps().packChanged();
            }
            case Opcode.SEND_EQUIPMENT_UPDATE -> {
                handleEquipmentUpdate(body);
                state.bankOps().packChanged();
            }
            case Opcode.SEND_EQUIPMENT_STATS -> handleEquipmentStats(body);
            case Opcode.SEND_PRAYERS_ACTIVE -> handlePrayersActive(body);
            case Opcode.SEND_COMBAT_STYLE -> {
                if (body.readableBytes() >= 1) {
                    state.setCombatStyle(body.readUnsignedByte());
                }
            }
            case Opcode.SEND_BUBBLE -> {
                if (body.readableBytes() >= 3) {
                    state.addRenderEvent(new RenderEvent.TeleBubble(
                            body.readUnsignedByte(), body.readUnsignedByte(), body.readUnsignedByte()));
                }
            }
            case Opcode.SEND_PLAY_SOUND -> {
                String name = readToNewline(body);
                if (name != null && !name.isEmpty()) {
                    state.addRenderEvent(new RenderEvent.Sound(name));
                }
            }
            case Opcode.SEND_PRIVACY_SETTINGS -> handlePrivacySettings(body);
            case Opcode.SEND_GAME_SETTINGS -> handleGameSettings(body);
            case Opcode.SEND_WELCOME_INFO -> handleWelcomeInfo(body);
            case Opcode.SEND_SYSTEM_UPDATE -> {
                if (body.readableBytes() >= 2) {
                    state.hud().systemUpdateTimer(body.readUnsignedShort());
                }
            }
            case Opcode.SEND_ELIXIR -> {
                if (body.readableBytes() >= 2) {
                    state.hud().elixirTimer(body.readUnsignedShort());
                }
            }
            case Opcode.SEND_EXPERIENCE_TOGGLE -> {
                if (body.readableBytes() >= 1) {
                    state.hud().experienceFrozen(body.readUnsignedByte());
                }
            }
            case Opcode.SEND_EXPSHARED -> {
                if (body.readableBytes() >= 2) {
                    state.hud().experienceShared(body.readUnsignedShort());
                }
            }
            case Opcode.SEND_APPEARANCE_KEEPALIVE -> {}
            case Opcode.SEND_INPUT_BOX -> state.hud().inputBoxPrompt(readToNewline(body));
            case Opcode.SEND_BANK_PIN_INTERFACE -> {
                if (body.readableBytes() >= 1) {
                    state.hud().bankPinOpen(body.readUnsignedByte() != 0);
                }
            }
            case Opcode.SEND_STATUS_PROGRESS_BAR -> handleProgressBar(body);
            case Opcode.SEND_FRIEND_UPDATE -> handleFriendUpdate(body);
            case Opcode.SEND_IGNORE_LIST -> handleIgnoreList(body);
            case Opcode.SEND_PRIVATE_MESSAGE -> handlePrivateMessage(body, false);
            case Opcode.SEND_PRIVATE_MESSAGE_SENT -> handlePrivateMessage(body, true);
            case Opcode.SEND_DUEL_WINDOW ->
                state.duel().windowOpened(body.readableBytes() >= 2 ? body.readUnsignedShort() : -1);
            case Opcode.SEND_DUEL_OPPONENTS_ITEMS -> state.duel().opponentStake(readStake(body));
            case Opcode.SEND_DUEL_SETTINGS -> {
                if (body.readableBytes() >= 4) {
                    state.duel()
                            .settings(
                                    body.readUnsignedByte(),
                                    body.readUnsignedByte(),
                                    body.readUnsignedByte(),
                                    body.readUnsignedByte());
                }
            }
            case Opcode.SEND_DUEL_ACCEPTED -> state.duel().myAccepted(readAcceptFlag(body));
            case Opcode.SEND_DUEL_OTHER_ACCEPTED -> state.duel().otherAccepted(readAcceptFlag(body));
            case Opcode.SEND_DUEL_CONFIRMWINDOW -> handleDuelConfirm(body);
            case Opcode.SEND_DUEL_CLOSE -> state.duel().close();
            case Opcode.SEND_CLAN -> handleClan(body);
            case Opcode.SEND_PARTY -> handleParty(body);
            case Opcode.SEND_KILL_ANNOUNCEMENT -> {
                String victim = readNlString(body);
                String attacker = readNlString(body);
                long killType = body.readableBytes() >= 4 ? body.readInt() : 0;
                state.noteKillAnnouncement(new KillAnnouncement(victim, attacker, killType));
            }
            case Opcode.SEND_ON_BLACK_HOLE -> {
                if (body.readableBytes() >= 1) {
                    state.setOnBlackHole(body.readUnsignedByte() != 0);
                }
            }
            case Opcode.SEND_IRONMAN -> handleIronman(body);
            case Opcode.SEND_FISHING_TRAWLER -> handleTrawler(body);
            case Opcode.SEND_AUCTION_LIST -> handleAuctionList(body);
            case Opcode.SEND_ONLINE_LIST -> handleOnlineList(body);
            case Opcode.SEND_OPENPK_POINTS -> {
                if (body.readableBytes() >= 8) {
                    state.setOpenPkPoints(body.readLong());
                }
            }
            case Opcode.SEND_OPENPK_POINTS_TO_GP_RATIO -> {
                if (body.readableBytes() >= 8) {
                    state.setOpenPkPointsToGpRatio(body.readLong());
                }
            }
            case Opcode.SEND_UPDATE_IGNORE_LIST_BECAUSE_NAME_CHANGE -> {}
            case Opcode.SEND_UNLOCKED_APPEARANCES -> handleUnlockedAppearances(body);
            case Opcode.SEND_BANK_PRESET -> handleBankPreset(body);
            case Opcode.SEND_SERVER_CONFIGS -> {
                try {
                    PreLoginConfigs cfg = PreLoginConfigs.parse(body);
                    if (replay) {
                        state.setWireFlags(cfg);
                    } else {
                        state.observeServerConfigs(cfg);
                    }
                } catch (RuntimeException e) {
                    if (!replay && CONFIGS_UNPARSEABLE_LOGGED.compareAndSet(false, true)) {
                        LOG.warn("WORLD: server-configs packet unparseable, not checked: {}", e.toString());
                    }
                }
            }
            case Opcode.SEND_LOGOUT_REQUEST_CONFIRM,
                    Opcode.SEND_CANT_LOGOUT,
                    Opcode.SEND_OPEN_RECOVERY,
                    Opcode.SEND_OPEN_DETAILS,
                    Opcode.SEND_28_BYTES_UNUSED -> {
                if (op == Opcode.SEND_CANT_LOGOUT
                        || op == Opcode.SEND_LOGOUT_REQUEST_CONFIRM
                        || op == Opcode.SEND_OPEN_DETAILS
                        || op == Opcode.SEND_OPEN_RECOVERY) {
                    state.noteSignal(op);
                }
            }
            default -> log.debug("[{}] unhandled opcode {}/{} ({} bytes)", accountName, op, wire, body.readableBytes());
        }
    }

    private void handleEquipment(ByteBuf body) {
        if (body.readableBytes() < 1) {
            return;
        }
        int count = body.readUnsignedByte();
        List<WornItem> items = new ArrayList<>(count);
        for (int i = 0; i < count && body.readableBytes() >= 3; i++) {
            int wieldPos = body.readUnsignedByte();
            int catalogId = body.readUnsignedShort();
            int amount = 1;
            if (itemDefs != null && itemDefs.isStackable(catalogId)) {
                if (body.readableBytes() < 4) {
                    return;
                }
                amount = body.readInt();
            }
            items.add(new WornItem(wieldPos, catalogId, amount));
        }
        state.setEquipment(items);
    }

    private void handleEquipmentUpdate(ByteBuf body) {
        if (body.readableBytes() < 3) {
            return;
        }
        int slot = body.readUnsignedByte();
        int catalogId = body.readUnsignedShort();
        if (catalogId == 0xFFFF) {
            state.setEquipmentSlot(slot, -1, 0);
            return;
        }
        int amount = 1;
        if (itemDefs != null && itemDefs.isStackable(catalogId)) {
            if (body.readableBytes() < 4) {
                return;
            }
            amount = body.readInt();
        }
        state.setEquipmentSlot(slot, catalogId, amount);
    }

    private void handleEquipmentStats(ByteBuf body) {
        if (body.readableBytes() < 5 + 20) {
            return;
        }
        body.skipBytes(5);
        int[] stats = new int[5];
        for (int i = 0; i < 5; i++) {
            stats[i] = body.readInt();
        }
        state.setEquipmentStats(stats);
    }

    private void handlePrayersActive(ByteBuf body) {
        int n = body.readableBytes();
        int[] flags = new int[n];
        for (int i = 0; i < n; i++) {
            flags[i] = body.readUnsignedByte();
        }
        state.setPrayersActive(flags);
    }

    private void handlePrivacySettings(ByteBuf body) {
        if (body.readableBytes() < 4) {
            return;
        }
        state.hud()
                .privacy(
                        body.readUnsignedByte(),
                        body.readUnsignedByte(),
                        body.readUnsignedByte(),
                        body.readUnsignedByte());
    }

    private void handleGameSettings(ByteBuf body) {
        if (body.readableBytes() < 3) {
            return;
        }
        int cameraAuto = body.readUnsignedByte();
        int mouseButtonOne = body.readUnsignedByte();
        int soundDisabled = body.readUnsignedByte();
        int n = body.readableBytes();
        int[] opts = new int[n];
        for (int i = 0; i < n; i++) {
            opts[i] = body.readUnsignedByte();
        }
        state.hud().gameSettings(cameraAuto, mouseButtonOne, soundDisabled, opts);
    }

    private void handleWelcomeInfo(ByteBuf body) {
        String lastIp = readNlString(body);
        if (body.readableBytes() < 4) {
            return;
        }
        int daysSinceLogin = body.readUnsignedShort();
        int recoveryDays = body.readUnsignedShort();
        state.hud().welcome(lastIp, daysSinceLogin, recoveryDays);
    }

    private void handleProgressBar(ByteBuf body) {
        if (body.readableBytes() < 1) {
            return;
        }
        int iface = body.readUnsignedByte();
        int delay = 0;
        int repeat = 0;
        if (iface > 0 && iface != 2) {
            if (iface == 1) {
                if (body.readableBytes() < 2) {
                    return;
                }
                delay = body.readUnsignedShort();
            }
            if (body.readableBytes() >= 1) {
                repeat = body.readUnsignedByte();
            }
        }
        state.hud().progress(iface, delay, repeat);
    }

    private void handleFriendUpdate(ByteBuf body) {
        String name = readNlString(body);
        String formerName = readNlString(body);
        if (body.readableBytes() < 1) {
            return;
        }
        int status = body.readUnsignedByte();
        String world = body.isReadable() ? readNlString(body) : "";
        state.updateFriend(new FriendEntry(name, formerName, status, world));
    }

    private void handleIgnoreList(ByteBuf body) {
        if (body.readableBytes() < 1) {
            return;
        }
        int size = body.readUnsignedByte();
        List<String> names = new ArrayList<>(size);
        for (int i = 0; i < size && body.isReadable(); i++) {
            String name = readNlString(body);
            readNlString(body);
            readNlString(body);
            readNlString(body);
            names.add(name);
        }
        state.setIgnoreList(names);
    }

    private void handlePrivateMessage(ByteBuf body, boolean sent) {
        String name = readNlString(body);
        String formerName = "";
        long icon = 0;
        if (!sent) {
            formerName = readNlString(body);
            if (body.readableBytes() < 4) {
                return;
            }
            icon = body.readUnsignedInt();
        }
        String message = readRscString(body);
        state.notePrivateMessage(new PrivateMessage(name, formerName, icon, message, sent));
    }

    private static String readRscString(ByteBuf body) {
        if (!body.isReadable()) {
            return "";
        }
        int peek = body.getUnsignedByte(body.readerIndex());
        int len;
        if ((peek & 0x80) == 0) {
            len = body.readUnsignedByte();
        } else {
            if (body.readableBytes() < 2) {
                return "";
            }
            len = body.readUnsignedShort() - 32768;
        }
        byte[] rest = new byte[body.readableBytes()];
        body.readBytes(rest);
        return Huffman.decode(rest, 0, len);
    }

    private List<Duel.StakeItem> readStake(ByteBuf body) {
        if (body.readableBytes() < 1) {
            return List.of();
        }
        int count = body.readUnsignedByte();
        boolean notes = state.wantBankNotes();
        int perItem = 2 + (notes ? 1 : 0) + 4;
        List<Duel.StakeItem> items = new ArrayList<>(count);
        for (int i = 0; i < count && body.readableBytes() >= perItem; i++) {
            int id = body.readUnsignedShort();
            boolean noted = notes && body.readUnsignedByte() != 0;
            int amount = body.readInt();
            items.add(new Duel.StakeItem(id, noted, amount));
        }
        return items;
    }

    private void handleDuelConfirm(ByteBuf body) {
        String opponentName = readNlString(body);
        List<Duel.StakeItem> opponentStake = readStake(body);
        List<Duel.StakeItem> myStake = readStake(body);
        state.duel().confirm(opponentName, opponentStake, myStake);
        if (body.readableBytes() >= 4) {
            state.duel()
                    .settings(
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte());
        }
    }

    private void handleClan(ByteBuf body) {
        if (body.readableBytes() < 1) {
            return;
        }
        MutableClan c = state.clan();
        switch (body.readUnsignedByte()) {
            case 0 -> {
                String clanName = readNlString(body);
                String clanTag = readNlString(body);
                String leaderName = readNlString(body);
                boolean isLeader = body.readableBytes() >= 1 && body.readUnsignedByte() == 1;
                int size = body.readableBytes() >= 1 ? body.readUnsignedByte() : 0;
                List<Clan.Member> members = new ArrayList<>(size);
                for (int i = 0; i < size && body.isReadable(); i++) {
                    String name = readNlString(body);
                    if (body.readableBytes() < 2) {
                        break;
                    }
                    int rank = body.readUnsignedByte();
                    boolean online = body.readUnsignedByte() != 0;
                    members.add(new Clan.Member(name, rank, online));
                }
                c.joined(clanName, clanTag, leaderName, isLeader, members);
            }
            case 1 -> c.left();
            case 2 -> c.invited(new Clan.Invite(readNlString(body), readNlString(body)));
            case 3 -> {
                if (body.readableBytes() >= 5) {
                    c.settings(
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte());
                }
            }
            case 4 -> {
                if (body.readableBytes() < 2) {
                    return;
                }
                int total = body.readUnsignedShort();
                List<Clan.ClanInfo> list = new ArrayList<>(total);
                for (int i = 0; i < total && body.readableBytes() >= 2; i++) {
                    int clanId = body.readUnsignedShort();
                    String name = readNlString(body);
                    String tag = readNlString(body);
                    if (body.readableBytes() < 8) {
                        break;
                    }
                    int msize = body.readUnsignedByte();
                    boolean canJoin = body.readUnsignedByte() != 0;
                    int points = body.readInt();
                    int rank = body.readUnsignedShort();
                    list.add(new Clan.ClanInfo(clanId, name, tag, msize, canJoin, points, rank));
                }
                c.searchResults(list);
            }
            default -> {}
        }
    }

    private void handleParty(ByteBuf body) {
        if (body.readableBytes() < 1) {
            return;
        }
        MutableParty p = state.party();
        switch (body.readUnsignedByte()) {
            case 0 -> {
                String leaderName = readNlString(body);
                boolean isLeader = body.readableBytes() >= 1 && body.readUnsignedByte() == 1;
                int size = body.readableBytes() >= 1 ? body.readUnsignedByte() : 0;
                List<Party.Member> members = new ArrayList<>(size);
                for (int i = 0; i < size && body.isReadable(); i++) {
                    String name = readNlString(body);
                    if (body.readableBytes() < 19) {
                        break;
                    }
                    int rank = body.readUnsignedByte();
                    boolean online = body.readUnsignedByte() != 0;
                    int curHp = body.readUnsignedByte();
                    int maxHp = body.readUnsignedByte();
                    int cb = body.readUnsignedByte();
                    boolean skull = body.readUnsignedByte() != 0;
                    boolean dead = body.readUnsignedByte() != 0;
                    boolean shareLoot = body.readUnsignedByte() != 0;
                    int total = body.readUnsignedByte();
                    boolean inCombat = body.readUnsignedByte() != 0;
                    int shareExp = body.readUnsignedByte();
                    long accrued = body.readLong();
                    members.add(new Party.Member(
                            name, rank, online, curHp, maxHp, cb, skull, dead, shareLoot, total, inCombat, shareExp,
                            accrued));
                }
                p.joined(leaderName, isLeader, members);
            }
            case 1 -> p.left();
            case 2 -> p.invited(new Party.Invite(readNlString(body), readNlString(body)));
            case 3 -> {
                if (body.readableBytes() >= 5) {
                    p.settings(
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte(),
                            body.readUnsignedByte());
                }
            }
            case 4 -> {
                if (body.readableBytes() < 2) {
                    return;
                }
                int total = body.readUnsignedShort();
                List<Party.PartyInfo> list = new ArrayList<>(total);
                for (int i = 0; i < total && body.readableBytes() >= 10; i++) {
                    int partyId = body.readUnsignedShort();
                    int msize = body.readUnsignedByte();
                    boolean canJoin = body.readUnsignedByte() != 0;
                    int points = body.readInt();
                    int rank = body.readUnsignedShort();
                    list.add(new Party.PartyInfo(partyId, msize, canJoin, points, rank));
                }
                p.searchResults(list);
            }
            default -> {}
        }
    }

    private void handleIronman(ByteBuf body) {
        if (body.readableBytes() < 2) {
            return;
        }
        int iface = body.readUnsignedByte();
        int action = body.readUnsignedByte();
        int type = 0;
        int restriction = 0;
        if (action == 0 && body.readableBytes() >= 2) {
            type = body.readUnsignedByte();
            restriction = body.readUnsignedByte();
        }
        state.setIronman(new IronmanHud(iface, action, type, restriction));
    }

    private void handleTrawler(ByteBuf body) {
        if (body.readableBytes() < 2) {
            return;
        }
        int iface = body.readUnsignedByte();
        int action = body.readUnsignedByte();
        int water = 0;
        int fish = 0;
        int mins = 0;
        int broken = 0;
        if (action == 1 && body.readableBytes() >= 6) {
            water = body.readUnsignedShort();
            fish = body.readUnsignedShort();
            mins = body.readUnsignedByte();
            broken = body.readUnsignedByte();
        }
        state.setTrawler(new TrawlerHud(iface, action, water, fish, mins, broken));
    }

    private void handleOnlineList(ByteBuf body) {
        if (body.readableBytes() < 2) {
            return;
        }
        int count = body.readUnsignedShort();
        List<OnlinePlayer> list = new ArrayList<>(count);
        for (int i = 0; i < count && body.isReadable(); i++) {
            String name = readNlString(body);
            if (body.readableBytes() < 4) {
                break;
            }
            long icon = body.readUnsignedInt();
            String location = readNlString(body);
            list.add(new OnlinePlayer(name, icon, location));
        }
        state.setOnlineList(list);
        state.noteSignal(Opcode.SEND_ONLINE_LIST);
    }

    private void handleAuctionList(ByteBuf body) {
        if (!body.isReadable()) {
            return;
        }
        int type = body.readUnsignedByte();
        if (type == 0) {
            state.auction().listCleared(state.sessionTick());
            return;
        }
        if (type != 1 || body.readableBytes() < 2) {
            return;
        }
        int count = body.readUnsignedShort();
        List<AuctionHouse.Listing> chunk = new ArrayList<>(count);
        for (int i = 0; i < count && body.readableBytes() >= 17; i++) {
            int auctionId = body.readInt();
            int itemId = body.readInt();
            int amountLeft = body.readInt();
            int price = body.readInt();
            boolean mine = body.readUnsignedByte() == 1;
            String seller = mine ? null : readNlString(body);
            if (!body.isReadable()) {
                break;
            }
            int hoursLeft = body.readUnsignedByte();
            chunk.add(new AuctionHouse.Listing(auctionId, itemId, amountLeft, price, mine, seller, hoursLeft));
        }
        state.auction().listed(chunk, state.sessionTick());
    }

    private void handleUnlockedAppearances(ByteBuf body) {
        if (body.readableBytes() < 24) {
            return;
        }
        int[] lens = new int[6];
        long total = 0;
        for (int i = 0; i < 6; i++) {
            lens[i] = body.readInt();
            if (lens[i] < 0 || lens[i] > 100_000) {
                return;
            }
            total += lens[i];
        }
        if (total > (long) body.readableBytes() * 8) {
            return;
        }
        byte[] bits = new byte[body.readableBytes()];
        body.readBytes(bits);
        boolean[][] arrs = new boolean[6][];
        int bit = 0;
        for (int a = 0; a < 6; a++) {
            arrs[a] = new boolean[lens[a]];
            for (int j = 0; j < lens[a]; j++, bit++) {
                int byteIdx = bit >> 3;
                int bitIdx = 7 - (bit & 7);
                arrs[a][j] = byteIdx < bits.length && ((bits[byteIdx] >> bitIdx) & 1) != 0;
            }
        }
        state.setUnlockedAppearances(new UnlockedAppearances(arrs[0], arrs[1], arrs[2], arrs[3], arrs[4], arrs[5]));
    }

    private static final int PRESET_INVENTORY_SLOTS = 30;

    private static final int PRESET_EQUIPMENT_SLOTS = 14;

    private void handleBankPreset(ByteBuf body) {
        if (body.readableBytes() < 2) {
            return;
        }
        int slot = body.readUnsignedShort();
        List<InventorySlot> inv = readPresetSlots(body, PRESET_INVENTORY_SLOTS, true);
        List<InventorySlot> eq = readPresetSlots(body, PRESET_EQUIPMENT_SLOTS, false);
        state.putBankPreset(new BankPreset(slot, inv, eq));
    }

    private List<InventorySlot> readPresetSlots(ByteBuf body, int count, boolean hasNoted) {
        List<InventorySlot> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!body.isReadable()) {
                break;
            }
            if (body.getUnsignedByte(body.readerIndex()) == 0xFF) {
                body.skipBytes(1);
                continue;
            }
            if (body.readableBytes() < 2) {
                break;
            }
            int id = body.readUnsignedShort();
            boolean noted = false;
            if (hasNoted) {
                if (!body.isReadable()) {
                    break;
                }
                noted = body.readUnsignedByte() != 0;
            }
            int amount = 1;
            if ((itemDefs != null && itemDefs.isStackable(id)) || noted) {
                if (body.readableBytes() < 4) {
                    break;
                }
                amount = body.readInt();
            }
            items.add(new InventorySlot(i, id, amount, false, noted));
        }
        return items;
    }

    private void handleNpcKills(ByteBuf body) {
        int totalKills = body.readInt();
        int npcId = body.readInt();
        int perTypeKills = body.readInt();
        if (npcId == -1) {
            log.info("[{}] NPC kill counter baseline: totalKills={}", accountName, totalKills);
            return;
        }
        state.noteKillCredit(new NpcKillCredit(npcId, totalKills, perTypeKills));
        log.info(
                "[{} t{}] NPC KILL credited: npcId={} perTypeKills={} totalKills={}",
                accountName,
                state.serverTick(),
                npcId,
                perTypeKills,
                totalKills);
    }

    private void handleQuests(ByteBuf body) {
        int isUpdate = body.readUnsignedByte();
        if (isUpdate != 1) {
            int n = body.readUnsignedByte();
            for (int i = 0; i < n; i++) {
                int id = body.readInt();
                int stage = body.readInt();
                String name = readNewlineTerminated(body);
                state.setQuestStage(id, stage);
                if (stage != 0) {
                    log.debug("[{}] quest {} ({}) stage={}", accountName, id, name, stage);
                }
            }
            state.markQuestTableReceived();
            log.info("[{}] quest table: {} quests", accountName, n);
        } else {
            int id = body.readInt();
            int stage = body.readInt();
            state.setQuestStage(id, stage);
            state.noteQuestStageUpdate(id);
            log.info("[{}] quest {} stage -> {}", accountName, id, stage);
        }
    }

    private static String readNewlineTerminated(ByteBuf body) {
        StringBuilder sb = new StringBuilder(24);
        while (body.isReadable()) {
            int b = body.readUnsignedByte();
            if (b == 0x0A) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private void handleLoadArea(ByteBuf body) {
        int idx = body.readShort();
        int wx = body.readShort();
        int wz = body.readShort();
        int pIdx = body.readShort();
        int pMul = body.readShort();
        state.setLoadArea(idx, wx, wz, pIdx, pMul);
        log.info("[{}] load area: serverIdx={} worldX={} worldZ={} plane={}", accountName, idx, wx, wz, pIdx);
    }

    private void handlePlayerCoords(ByteBuf body) {
        int totalBits = body.readableBytes() * 8;
        if (totalBits < 11 + 13 + 4 + 8) {
            return;
        }
        int ofs = 0;
        int rawX = RscPackets.readBits(body, ofs, 11);
        ofs += 11;
        int rawZ = RscPackets.readBits(body, ofs, 13);
        ofs += 13;
        int sprite = RscPackets.readBits(body, ofs, 4);
        ofs += 4;
        int cachedCount = RscPackets.readBits(body, ofs, 8);
        ofs += 8;
        state.setSelfCoords(rawX, rawZ, sprite);
        if (state.consumePositionJump()) {
            log.info(
                    "[{}] position JUMP ({},{}) → ({},{})",
                    accountName,
                    state.lastJumpFromX(),
                    state.lastJumpFromZ(),
                    state.lastJumpToX(),
                    state.lastJumpToZ());
        }

        List<MutablePlayer> cache = state.snapshotPlayersInView();
        PlayerCoords.Decoded wire = PlayerCoords.decode(body, ofs, totalBits, cachedCount, cache.size());
        if (wire == null) {
            if (!playerViewUndecodableWarned) {
                playerViewUndecodableWarned = true;
                log.warn(
                        "[{}] player-view: undecodable coords ({} cached, {} bits) — view kept",
                        accountName,
                        cachedCount,
                        totalBits);
            }
            return;
        }
        playerViewUndecodableWarned = false;
        if (wire.count() != cachedCount) {
            if (!playerViewWrapWarned) {
                playerViewWrapWarned = true;
                log.warn(
                        "[{}] player-view: server lists {} players, count wrapped to {}",
                        accountName,
                        wire.count(),
                        cachedCount);
            }
        } else {
            playerViewWrapWarned = false;
        }
        Set<Integer> resent = wire.resent();
        int[] held = PlayerCoords.align(wire.kind(), wire.count(), cache, resent, state.x(), state.z());

        state.beginPlayerTick();
        Set<Integer> tentativeDespawn = new HashSet<>();
        List<MutablePlayer> tentativeDespawnPlayers = new ArrayList<>();
        boolean[] named = new boolean[cache.size()];
        int unnamed = 0;
        for (int i = 0; i < wire.count(); i++) {
            if (held[i] < 0) {
                unnamed++;
                continue;
            }
            named[held[i]] = true;
            MutablePlayer p = cache.get(held[i]);
            p.setStepDir(-1);
            switch (wire.kind()[i]) {
                case PlayerCoords.REMOVE -> {
                    tentativeDespawn.add(p.serverIndex());
                    tentativeDespawnPlayers.add(p);
                    continue;
                }
                case PlayerCoords.SPRITE -> p.setSprite(wire.value()[i]);
                case PlayerCoords.WALK -> applyPlayerWalkDir(p, wire.value()[i]);
                default -> {}
            }
            state.appendPlayerInView(p);
        }
        int dropped = 0;
        for (int j = 0; j < cache.size(); j++) {
            if (!named[j]) {
                dropped++;
                tentativeDespawn.add(cache.get(j).serverIndex());
                tentativeDespawnPlayers.add(cache.get(j));
            }
        }
        state.notePlayersUnplaced(unnamed);
        if (unnamed > 0 || dropped > 0) {
            if (!playerViewDriftWarned) {
                playerViewDriftWarned = true;
                log.warn(
                        "[{}] player-view drift: server lists {}, we hold {} — {} unnamed, {} dropped",
                        accountName,
                        wire.count(),
                        cache.size(),
                        unnamed,
                        dropped);
            }
        } else {
            playerViewDriftWarned = false;
        }

        for (PlayerCoords.Spawn s : wire.spawns()) {
            int serverIndex = s.serverIndex();
            int x = state.x() + s.dx();
            int z = state.z() + s.dz();
            int sp = s.sprite();

            MutablePlayer existing = state.playerByServerIndex(serverIndex);
            if (existing != null) {
                boolean wasCached = tentativeDespawn.remove(serverIndex);
                existing.setStepDir(wasCached ? Player.stepDirOf(x - existing.x(), z - existing.z()) : -1);
                existing.setX(x);
                existing.setZ(z);
                existing.setSprite(sp);
                state.appendPlayerInView(existing);
            } else {
                state.spawnPlayer(serverIndex, x, z, sp);
            }
        }

        for (MutablePlayer p : tentativeDespawnPlayers) {
            if (tentativeDespawn.contains(p.serverIndex())) {
                state.despawnPlayer(p);
            }
        }

        for (MutablePlayer p : state.snapshotPlayersInView()) {
            p.noteTrailTick();
        }

        state.publishPlayerView();
    }

    private static void applyPlayerWalkDir(MutablePlayer p, int dir) {
        p.setX(p.x() + Player.stepDx(dir));
        p.setZ(p.z() + Player.stepDz(dir));
        p.setSprite(dir);
        p.setStepDir(dir);
    }

    private void handleNpcCoords(ByteBuf body) {
        int totalBits = body.readableBytes() * 8;
        int ofs = 0;
        int cachedCount = RscPackets.readBits(body, ofs, 8);
        ofs += 8;

        List<MutableNpc> cache = state.snapshotNpcsInView();
        // NPC coords come ahead of the tick's scenery, so a step is judged on the overlay both before and after it:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L80
        for (MutableNpc npc : cache) {
            npc.confirmStep(solid(npc.x(), npc.z()));
        }
        state.beginNpcTick();

        Set<Integer> tentativeDespawn = new HashSet<>();
        List<MutableNpc> tentativeDespawnNpcs = new ArrayList<>();

        for (int i = 0; i < cachedCount && i < cache.size(); i++) {
            MutableNpc npc = cache.get(i);
            int needsUpdate = RscPackets.readBits(body, ofs, 1);
            ofs += 1;
            if (needsUpdate == 0) {
                state.appendNpcInView(npc);
                continue;
            }
            int updateType = RscPackets.readBits(body, ofs, 1);
            ofs += 1;
            if (updateType == 1) {
                int nextSpriteOffset = RscPackets.readBits(body, ofs, 2);
                ofs += 2;
                if (nextSpriteOffset == 3) {
                    tentativeDespawn.add(npc.serverIndex());
                    tentativeDespawnNpcs.add(npc);
                    continue;
                }
                int low = RscPackets.readBits(body, ofs, 2);
                ofs += 2;
                npc.setSprite((nextSpriteOffset << 2) | low);
                state.appendNpcInView(npc);
            } else {
                int dir = RscPackets.readBits(body, ofs, 3);
                ofs += 3;
                applyWalkDir(npc, dir);
                state.appendNpcInView(npc);
            }
        }

        int newFrom = ofs;
        while (ofs + 34 < totalBits) {
            int serverIndex = RscPackets.readBits(body, ofs, 12);
            ofs += 12;
            int dx = RscPackets.readBits(body, ofs, 6);
            ofs += 6;
            int dz = RscPackets.readBits(body, ofs, 6);
            ofs += 6;
            int sprite = RscPackets.readBits(body, ofs, 4);
            ofs += 4;
            if (dx > 31) {
                dx -= 64;
            }
            if (dz > 31) {
                dz -= 64;
            }
            int id = RscPackets.readBits(body, ofs, 10);
            ofs += 10;
            int x = state.x() + dx;
            int z = state.z() + dz;

            MutableNpc existing = state.npcByServerIndex(serverIndex);
            // The server drops an NPC in combat or mid-teleport and re-adds it in the same packet under the same
            // index and id; a different id is another NPC that took the index freed earlier in the tick:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L222-L231
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/Server.java#L687-L715
            // An index listed more than once belongs to NPCs the server failed to register, which all carry index 0;
            // they share one object here, and replacing it would leave the view an entry short of the server's count:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/EntityList.java#L45-L53
            if (existing != null
                    && existing.id() != id
                    && tentativeDespawn.contains(serverIndex)
                    && knownUnder(cache, cachedCount, serverIndex) == 1
                    && newUnder(body, newFrom, totalBits, serverIndex) == 1) {
                tentativeDespawn.remove(serverIndex);
                state.despawnNpc(existing);
                existing = null;
            }
            if (existing != null) {
                existing.setX(x);
                existing.setZ(z);
                existing.setSprite(sprite);
                existing.setDesynced(false);
                tentativeDespawn.remove(serverIndex);
                state.appendNpcInView(existing);
            } else {
                state.spawnNpc(serverIndex, id, x, z, sprite);
            }
        }

        for (MutableNpc n : tentativeDespawnNpcs) {
            if (tentativeDespawn.contains(n.serverIndex())) {
                state.despawnNpc(n);
            }
        }

        state.publishNpcView();

        state.noteTickBoundary();
    }

    private static int knownUnder(List<MutableNpc> cache, int cachedCount, int serverIndex) {
        int n = 0;
        for (int i = 0; i < cachedCount && i < cache.size(); i++) {
            if (cache.get(i).serverIndex() == serverIndex) {
                n++;
            }
        }
        return n;
    }

    private static int newUnder(ByteBuf body, int from, int totalBits, int serverIndex) {
        int n = 0;
        for (int ofs = from; ofs + 34 < totalBits; ofs += 38) {
            if (RscPackets.readBits(body, ofs, 12) == serverIndex) {
                n++;
            }
        }
        return n;
    }

    private void applyWalkDir(MutableNpc npc, int dir) {
        int x = npc.x(), z = npc.z();
        if (dir == 2 || dir == 1 || dir == 3) {
            x++;
        }
        if (dir == 6 || dir == 5 || dir == 7) {
            x--;
        }
        if (dir == 4 || dir == 3 || dir == 5) {
            z++;
        }
        if (dir == 0 || dir == 1 || dir == 7) {
            z--;
        }
        npc.setX(x);
        npc.setZ(z);
        npc.setSprite(dir);
        npc.noteStep(solid(x, z));
    }

    private boolean solid(int x, int z) {
        CollisionMap map = state.serverData().collisionMap();
        return map != null && map.fullyBlocked(x, z, state.wallOverlay());
    }

    private void handleScenery(ByteBuf body) {
        int selfX = state.x(), selfZ = state.z();
        boolean stamp = state.hasScriptConsumer();
        WallOverlay overlay = state.wallOverlay();
        WallOverlay losOverlay = state.losOverlay();
        long[] removed = null;
        int removedCount = 0;
        while (body.readableBytes() >= 5) {
            int id = body.readUnsignedShort();
            int dx = body.readByte();
            int dz = body.readByte();
            int dir = body.readUnsignedByte();
            int x = selfX + dx, z = selfZ + dz;
            long tileKey = ((long) x << 32) | (z & 0xFFFFFFFFL);
            if (id == 60000) {
                if (removed == null) {
                    removed = new long[16];
                } else if (removedCount == removed.length) {
                    removed = java.util.Arrays.copyOf(removed, removedCount * 2);
                }
                removed[removedCount++] = tileKey;
            } else {
                for (int i = 0; i < removedCount; i++) {
                    if (removed[i] == tileKey) {
                        state.noteSceneryReplaced(x, z, id, dir);
                        break;
                    }
                }
            }
            GameObject prev = state.updateObject(id, x, z, dir);
            if (prev != null && stamp) {
                stampScenery(overlay, prev.id(), prev.x(), prev.z(), prev.dir(), false, false);
                stampScenery(losOverlay, prev.id(), prev.x(), prev.z(), prev.dir(), false, true);
            }
            if (id != 60000 && stamp) {
                stampScenery(overlay, id, x, z, dir, true, false);
                stampScenery(losOverlay, id, x, z, dir, true, true);
            }
        }
    }

    private static final int CHARGE_EFFECT_OBJECT = 1147;

    private static final Set<Integer> GOD_SPELL_EFFECT_OBJECTS = Set.of(1142, 1031, 1036);

    static boolean stampsCollision(int id, boolean losView) {
        return id != CHARGE_EFFECT_OBJECT && !(losView && GOD_SPELL_EFFECT_OBJECTS.contains(id));
    }

    private void stampScenery(WallOverlay overlay, int id, int x, int y, int dir, boolean add, boolean losView) {
        if (objectDefs == null) {
            return;
        }
        if (!stampsCollision(id, losView)) {
            return;
        }
        ObjectDef def = objectDefs.get(id);
        if (def == null) {
            return;
        }
        int typ = def.typ();
        if (typ != 1 && typ != 2) {
            return;
        }
        if (losView && CollisionMap.isSceneryProjectileClipAllowed(def)) {
            if (add) {
                overlay.addProjectilePass(x, y, dir, def.width(), def.height(), typ == 1);
            } else {
                overlay.removeProjectilePass(x, y, dir, def.width(), def.height(), typ == 1);
            }
        }
        if (typ == 2) {
            boolean stampWall = losView || stampsWalkerSceneryWall(objectDefs, id, x, y, dir);
            if (!stampWall) {
                return;
            }
            if (add) {
                overlay.addScenery(x, y, dir, def.width(), def.height());
            } else {
                overlay.removeScenery(x, y, dir, def.width(), def.height());
            }
        } else {
            if (add) {
                overlay.addSceneryFullBlock(x, y, dir, def.width(), def.height());
            } else {
                overlay.removeSceneryFullBlock(x, y, dir, def.width(), def.height());
            }
        }
    }

    private void handleUpdateNpc(ByteBuf body) {
        try {
            if (body.readableBytes() < 2) {
                return;
            }
            int updateCount = body.readUnsignedShort();
            for (int i = 0; i < updateCount && body.readableBytes() >= 3; i++) {
                int npcIdx = body.readUnsignedShort();
                int type = body.readUnsignedByte();
                switch (type) {
                    case 1 -> {
                        if (body.readableBytes() < 2) {
                            return;
                        }
                        int recipient = body.readShort();
                        StringBuilder sb = new StringBuilder(32);
                        while (body.isReadable()) {
                            int b = body.readUnsignedByte();
                            if (b == 10) {
                                break;
                            }
                            sb.append((char) b);
                        }
                        state.noteNpcChat(npcIdx, recipient, sb.toString());
                        MutableNpc chatNpc = state.npcByServerIndex(npcIdx);
                        if (chatNpc != null) {
                            chatNpc.setChatMessage(sb.toString(), state.serverTick());
                        }
                        boolean atUs = recipient == state.selfServerIndex();
                        if (atUs) {
                            rxLog.info(
                                    "[{} t{}] IN npc-say npcIdx={} text='{}'",
                                    accountName,
                                    state.serverTick(),
                                    npcIdx,
                                    sb);
                        } else if (rxLog.isDebugEnabled()) {
                            rxLog.debug(
                                    "[{} t{}] IN npc-say npcIdx={} recipient={} text='{}' (not us)",
                                    accountName,
                                    state.serverTick(),
                                    npcIdx,
                                    recipient,
                                    sb);
                        }
                    }
                    case 2 -> {
                        if (body.readableBytes() < 3) {
                            return;
                        }
                        int npcDmg = body.readUnsignedByte();
                        int curHits = body.readUnsignedByte();
                        int maxHits = body.readUnsignedByte();
                        state.noteNpcDamage(npcIdx, npcDmg);
                        MutableNpc dmgNpc = state.npcByServerIndex(npcIdx);
                        if (dmgNpc != null) {
                            dmgNpc.setHits(curHits, maxHits);
                            dmgNpc.noteDamage(npcDmg, state.serverTick());
                        }
                        state.addRenderEvent(new RenderEvent.DamageSplat(npcIdx, false, npcDmg));
                    }
                    case 3, 4 -> {
                        if (body.readableBytes() < 4) {
                            return;
                        }
                        int spriteType = body.readUnsignedShort();
                        int victim = body.readUnsignedShort();
                        state.addRenderEvent(new RenderEvent.Projectile(spriteType, npcIdx, true, victim, type == 4));
                    }
                    case 5 -> {
                        int skull = body.readUnsignedByte();
                        MutableNpc npc = state.npcByServerIndex(npcIdx);
                        if (npc != null) {
                            npc.setSkullType(skull);
                        }
                    }
                    case 6 -> {
                        int w1 = body.readUnsignedByte();
                        int w2 = body.readUnsignedByte();
                        MutableNpc npc = state.npcByServerIndex(npcIdx);
                        if (npc != null) {
                            npc.setWield(w1, w2);
                        }
                    }
                    case 7 -> {
                        int itemId = body.readUnsignedShort();
                        MutableNpc npc = state.npcByServerIndex(npcIdx);
                        if (npc != null) {
                            npc.setActionBubbleItemId(itemId);
                        }
                        state.noteActionBubbleEvent(npcIdx, false, itemId);
                    }
                    default -> {
                        return;
                    }
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
            // Truncated packet — give up cleanly.
        }
    }

    private void handleUpdatePlayers(ByteBuf body) {
        try {
            if (body.readableBytes() < 2) {
                return;
            }
            int updateCount = body.readUnsignedShort();
            int selfIdx = state.selfServerIndex();
            for (int i = 0; i < updateCount && body.readableBytes() >= 3; i++) {
                int playerIdx = body.readUnsignedShort();
                int type = body.readUnsignedByte();
                switch (type) {
                    case 0 -> {
                        if (body.readableBytes() < 2) {
                            return;
                        }
                        int itemId = body.readUnsignedShort();
                        if (playerIdx == selfIdx) {
                            state.noteSelfActionBubble(itemId);
                        } else {
                            MutablePlayer other = state.playerByServerIndex(playerIdx);
                            if (other != null) {
                                other.noteActionBubble(itemId, state.serverTick());
                            }
                        }
                        state.noteActionBubbleEvent(playerIdx, true, itemId);
                    }
                    case 1, 6, 7 -> {
                        int chatIcon = 0;
                        boolean muted = false;
                        boolean tutorial = false;
                        if (type == 1 || type == 7) {
                            if (body.readableBytes() < 4) {
                                return;
                            }
                            chatIcon = (int) body.readUnsignedInt();
                            if (type == 7) {
                                if (body.readableBytes() < 2) {
                                    return;
                                }
                                muted = body.readUnsignedByte() != 0;
                                tutorial = body.readUnsignedByte() != 0;
                            }
                        }
                        String chat = readToNewline(body);
                        if (chat != null && !chat.isEmpty()) {
                            if (playerIdx == selfIdx) {
                                state.noteSelfChat(chat, chatIcon, type, muted, tutorial);
                            } else {
                                state.notePlayerChat(playerIdx, chat, chatIcon, type, muted, tutorial);
                            }
                        }
                    }
                    case 2 -> {
                        if (body.readableBytes() < 3) {
                            return;
                        }
                        int dmg = body.readUnsignedByte();
                        int curHits = body.readUnsignedByte();
                        int maxHits = body.readUnsignedByte();
                        if (playerIdx == selfIdx) {
                            state.noteSelfDamage(dmg);
                            state.setHits(curHits, maxHits);
                        } else {
                            MutablePlayer pi = state.playerByServerIndex(playerIdx);
                            if (pi != null) {
                                pi.setHits(curHits, maxHits);
                                pi.noteDamage(dmg, state.serverTick());
                            }
                        }
                        state.addRenderEvent(new RenderEvent.DamageSplat(playerIdx, true, dmg));
                    }
                    case 3, 4 -> {
                        if (body.readableBytes() < 4) {
                            return;
                        }
                        int spriteType = body.readUnsignedShort();
                        int victim = body.readUnsignedShort();
                        state.addRenderEvent(
                                new RenderEvent.Projectile(spriteType, playerIdx, false, victim, type == 4));
                    }
                    case 5 -> {
                        String username = readToNewline(body);
                        if (body.readableBytes() < 1) {
                            return;
                        }
                        int itemCount = body.readUnsignedByte();
                        if (body.readableBytes() < itemCount * 2 + 7) {
                            return;
                        }
                        int[] equip = new int[itemCount];
                        for (int e = 0; e < itemCount; e++) {
                            equip[e] = body.readUnsignedShort();
                        }
                        int hair = body.readUnsignedByte();
                        int top = body.readUnsignedByte();
                        int trouser = body.readUnsignedByte();
                        int skin = body.readUnsignedByte();
                        int combatLevel = body.readUnsignedByte();
                        int skull = body.readUnsignedByte();
                        int clanFlag = body.readUnsignedByte();
                        String clanTag = clanFlag == 1 ? readToNewline(body) : null;
                        if (body.readableBytes() < 7) {
                            return;
                        }
                        boolean invisible = body.readUnsignedByte() == 1;
                        boolean invulnerable = body.readUnsignedByte() == 1;
                        int groupId = body.readUnsignedByte();
                        long icon = body.readUnsignedInt();
                        if (playerIdx == selfIdx) {
                            state.setAppearanceScreen(false);
                            state.selfPanel()
                                    .appearance(Appearance.of(
                                            equip,
                                            hair,
                                            top,
                                            trouser,
                                            skin,
                                            combatLevel,
                                            skull,
                                            clanTag,
                                            invisible,
                                            invulnerable,
                                            groupId,
                                            icon));
                        } else {
                            MutablePlayer pi = state.playerByServerIndex(playerIdx);
                            if (pi != null) {
                                if (LOG.isDebugEnabled() && !username.equals(pi.username())) {
                                    LOG.debug(
                                            "player idx={} username {} -> {} (self idx={})",
                                            playerIdx,
                                            pi.username(),
                                            username,
                                            selfIdx);
                                }
                                pi.setUsername(username);
                                pi.setAppearance(Appearance.of(
                                        equip,
                                        hair,
                                        top,
                                        trouser,
                                        skin,
                                        combatLevel,
                                        skull,
                                        clanTag,
                                        invisible,
                                        invulnerable,
                                        groupId,
                                        icon));
                            }
                        }
                    }
                    case 9 -> {
                        if (body.readableBytes() < 2) {
                            return;
                        }
                        int curHits = body.readUnsignedByte();
                        int maxHits = body.readUnsignedByte();
                        if (playerIdx == selfIdx) {
                            state.setHits(curHits, maxHits);
                        } else {
                            MutablePlayer pi = state.playerByServerIndex(playerIdx);
                            if (pi != null) {
                                pi.setHits(curHits, maxHits);
                            }
                        }
                    }
                    default -> {
                        return;
                    }
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
            // Truncated packet — give up cleanly.
        }
    }

    private static String readToNewline(ByteBuf body) {
        StringBuilder sb = new StringBuilder(16);
        while (body.isReadable()) {
            int b = body.readUnsignedByte();
            if (b == 10) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private void handleGroundItems(ByteBuf body) {
        int selfX = state.x(), selfZ = state.z();
        boolean notes = state.wantBankNotes();
        while (body.readableBytes() >= 3) {
            int peek = body.getUnsignedByte(body.readerIndex());
            if (peek == 255) {
                body.skipBytes(1);
                int dx = body.readByte();
                int dz = body.readByte();
                if (notes) {
                    if (!body.isReadable()) {
                        return;
                    }
                    body.skipBytes(1);
                }
                state.clearGroundItemsAt(selfX + dx, selfZ + dz);
            } else {
                if (body.readableBytes() < 4) {
                    return;
                }
                int rawId = body.readUnsignedShort();
                int dx = body.readByte();
                int dz = body.readByte();
                boolean noted = false;
                if (notes) {
                    if (!body.isReadable()) {
                        return;
                    }
                    noted = body.readUnsignedByte() != 0;
                }
                int x = selfX + dx, z = selfZ + dz;
                if ((rawId & 0x8000) != 0) {
                    state.removeGroundItem(rawId & 0x7FFF, x, z);
                } else {
                    state.addGroundItem(rawId, x, z, noted);
                }
            }
        }
    }

    private void handleRemoveWorldEntity(ByteBuf body) {
        int selfX = state.x(), selfZ = state.z();
        while (body.readableBytes() >= 4) {
            int dx = body.readShort();
            int dz = body.readShort();
            int chunkX = (selfX + dx) >> 3;
            int chunkZ = (selfZ + dz) >> 3;
            state.noteClearedChunk(chunkX, chunkZ);
            int removed = state.clearChunk(chunkX, chunkZ);
            if (log.isDebugEnabled() && removed > 0) {
                log.debug("[{}] chunk-clear ({},{}) removed {} entities", accountName, chunkX, chunkZ, removed);
            }
        }
    }

    private void handleTradeWindow(ByteBuf body) {
        if (body.readableBytes() < 2) {
            return;
        }
        int opponent = body.readUnsignedShort();
        state.openTrade(opponent);
        log.debug("[{}] trade: window opened with serverIndex {}", accountName, opponent);
    }

    private void handleTradeItems(ByteBuf body) {
        List<InventorySlot> otherOffer = readTradeOffer(body);
        List<InventorySlot> myOffer = readTradeOffer(body);
        state.setTradeOffers(myOffer, otherOffer);
        log.debug(
                "[{}] trade: offers updated (mine={}, theirs={})",
                accountName,
                describeOffer(myOffer),
                describeOffer(otherOffer));
    }

    private void handleTradeConfirm(ByteBuf body) {
        String partner = readTerminatedString(body);
        List<InventorySlot> otherOffer = readTradeOffer(body);
        List<InventorySlot> myOffer = readTradeOffer(body);
        state.setTradeConfirmScreen(partner, myOffer, otherOffer);
        log.debug(
                "[{}] trade: confirm screen with '{}' (mine={}, theirs={})",
                accountName,
                partner,
                describeOffer(myOffer),
                describeOffer(otherOffer));
    }

    private static String readTerminatedString(ByteBuf body) {
        StringBuilder sb = new StringBuilder();
        while (body.isReadable()) {
            int b = body.readUnsignedByte();
            if (b == 0x0A) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static String describeOffer(List<InventorySlot> offer) {
        StringBuilder sb = new StringBuilder().append(offer.size()).append('[');
        for (InventorySlot s : offer) {
            sb.append(s.item().id()).append('x').append(s.amount()).append(s.noted() ? "n " : " ");
        }
        return sb.append(']').toString();
    }

    private List<InventorySlot> readTradeOffer(ByteBuf body) {
        if (!body.isReadable()) {
            return List.of();
        }
        boolean notes = state.wantBankNotes() && !legacyKeyframe;
        int entry = notes ? 7 : 6;
        int count = body.readUnsignedByte();
        List<InventorySlot> items = new ArrayList<>(count);
        for (int i = 0; i < count && body.readableBytes() >= entry; i++) {
            int id = body.readUnsignedShort() & 0x7FFF;
            boolean noted = notes && body.readUnsignedByte() != 0;
            int amount = body.readInt();
            items.add(new InventorySlot(i, id, amount, false, noted));
        }
        return items;
    }

    private static boolean readAcceptFlag(ByteBuf body) {
        return body.isReadable() && body.readUnsignedByte() != 0;
    }

    private void handleWallObjects(ByteBuf body) {
        int selfX = state.x(), selfZ = state.z();
        boolean stamp = state.hasScriptConsumer();
        WallOverlay overlay = state.wallOverlay();
        WallOverlay losOverlay = state.losOverlay();
        while (body.readableBytes() >= 5) {
            int id = body.readUnsignedShort();
            int dx = body.readByte();
            int dz = body.readByte();
            int dir = body.readUnsignedByte();
            int x = selfX + dx;
            int z = selfZ + dz;
            if (stamp) {
                overlay.removeBoundary(x, z, dir);
                losOverlay.removeBoundary(x, z, dir);
            }
            boolean collidable = id != 60000 && doorDefs != null && doorDefs.doorType(id) == 1;
            if (stamp && streamedBoundaryStampsWall(doorDefs, id, x, z, dir)) {
                overlay.addBoundary(x, z, dir);
            }
            if (stamp && collidable) {
                losOverlay.addBoundary(x, z, dir);
            }
            state.updateWallObject(id, x, z, dir);
            if (log.isDebugEnabled()) {
                log.debug("[{}] wall {} at ({},{}) dir {}", accountName, id, x, z, dir);
            }
        }
    }

    static boolean streamedBoundaryStampsWall(DoorDefs doorDefs, int id, int x, int z, int dir) {
        return id != 60000 && doorDefs != null && stampsWalkerWall(doorDefs, id, x, z, dir);
    }

    static boolean stampsWalkerWall(DoorDefs doorDefs, int id, int x, int z, int dir) {
        return doorDefs.doorType(id) == 1 && !doorDefs.isGenericFreeDoor(id);
    }

    static boolean stampsWalkerSceneryWall(ObjectDefs objectDefs, int id, int x, int y, int dir) {
        return !objectDefs.isGenericFreeDoor(id);
    }

    private void handleInventory(ByteBuf body) {
        int n = body.readUnsignedByte();
        List<InventorySlot> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int rawId = body.readUnsignedShort();
            int eqFlag = body.readUnsignedByte();
            int noted = body.readUnsignedByte();
            int id = rawId & 0x7FFF;
            int amount;
            if (itemDefs.isStackable(id) || noted != 0) {
                amount = body.readInt();
            } else {
                amount = 1;
            }
            items.add(new InventorySlot(i, id, amount, eqFlag != 0, noted != 0));
        }
        state.setInventory(items);
        log.debug("[{}] inventory: {} slots", accountName, n);
    }

    private void handleInventoryUpdateItem(ByteBuf body) {
        int slot = body.readUnsignedByte();
        int rawId = body.readUnsignedShort();
        int noted = body.readUnsignedByte();
        int id = rawId & 0x7FFF;
        boolean equipped = (rawId & 0x8000) != 0;
        int amount = 1;
        if (itemDefs.isStackable(id) || noted != 0) {
            amount = body.readInt();
        }
        state.setInventorySlot(slot, new InventorySlot(slot, id, amount, equipped, noted != 0));
        log.debug("[{}] inv slot {} = id={} amt={} equipped={}", accountName, slot, id, amount, equipped);
    }

    private void handleInventoryRemoveItem(ByteBuf body) {
        int slot = body.readUnsignedByte();
        state.removeInventorySlot(slot);
        log.debug("[{}] inv slot {} removed", accountName, slot);
    }

    private void handleStats(ByteBuf body) {
        int n = Math.max(0, (body.readableBytes() - 1) / 6);
        state.setServerSkillCount(n);
        int stored = Math.min(n, Skill.COUNT);
        int[] cur = new int[stored], max = new int[stored];
        long[] xp = new long[stored];
        for (int i = 0; i < n && body.isReadable(); i++) {
            int v = body.readUnsignedByte();
            if (i < stored) {
                cur[i] = v;
            }
        }
        for (int i = 0; i < n && body.isReadable(); i++) {
            int v = body.readUnsignedByte();
            if (i < stored) {
                max[i] = v;
            }
        }
        for (int i = 0; i < n && body.readableBytes() >= 4; i++) {
            long v = body.readUnsignedInt();
            if (i < stored) {
                xp[i] = v;
            }
        }
        state.setAllSkills(cur, max, xp);
        if (body.isReadable()) {
            state.setQuestPoints(body.readUnsignedByte());
        }
        log.debug("[{}] stats snapshot loaded ({} skills)", accountName, n);
    }

    private void handleStat(ByteBuf body) {
        int id = body.readUnsignedByte();
        int cur = body.readUnsignedByte();
        int max = body.readUnsignedByte();
        long xp = body.readUnsignedInt();
        state.setSkill(id, cur, max, xp);
        log.debug("[{}] skill {} = {}/{} xp={}", accountName, id, cur, max, xp);
    }

    private void handleExperience(ByteBuf body) {
        int id = body.readUnsignedByte();
        long xp = body.readUnsignedInt();
        state.setSkillXp(id, xp);
        log.debug("[{}] skill {} xp={}", accountName, id, xp);
    }

    private boolean bankScreenOpens(int previous) {
        return previous != Opcode.SEND_BANK_UPDATE.wire() || !state.bankScreenFollowsEveryUpdate();
    }

    private void handleBankOpen(ByteBuf body, boolean opens) {
        MutableBank bank = state.bank();
        int count = body.readUnsignedShort();
        int maxSize = body.readUnsignedShort();
        List<BankSlot> slots = new ArrayList<>(count);
        for (int i = 0; i < count && body.readableBytes() >= 6; i++) {
            int id = body.readUnsignedShort();
            int amt = body.readInt();
            slots.add(new BankSlot(i, id, amt));
        }
        bank.setCapacity(maxSize);
        bank.setAll(slots);
        state.markBankObserved();
        log.info(
                "[{}] bank: replaced with {} slots (max={}){}",
                accountName,
                slots.size(),
                maxSize,
                opens ? "" : ", a refresh (the screen stays as it was)");
    }

    private void handleBankUpdate(ByteBuf body) {
        MutableBank bank = state.bank();
        int slot = body.readUnsignedByte();
        int id = body.readUnsignedShort();
        int amt = body.readInt();
        answerBankLine(bank, slot, id, amt);
        if (!state.bankObserved()) {
            List<BankSlot> kept = new ArrayList<>();
            for (BankSlot s : bank.snapshot().slots()) {
                if (s.item().id() != id) {
                    kept.add(new BankSlot(kept.size(), s.item(), s.amount()));
                }
            }
            if (amt > 0) {
                kept.add(new BankSlot(kept.size(), id, amt));
            }
            bank.setAll(kept);
            log.debug("[{}] bank: seed adjusted by item {} -> {}", accountName, id, amt);
            return;
        }
        if (amt == 0) {
            bank.removeSlot(slot);
        } else {
            bank.setSlot(slot, new BankSlot(slot, id, amt));
        }
    }

    private void answerBankLine(MutableBank bank, int slot, int id, int amt) {
        if (amt > 0 || id != 0) {
            state.bankOps().bankUpdated(id, bank.snapshot().count(id), amt);
            return;
        }
        if (!state.bankObserved()) {
            return;
        }
        List<BankSlot> slots = bank.snapshot().slots();
        Map<Integer, Long> emptied = new LinkedHashMap<>();
        for (int i = slot; i < slots.size(); i += 256) {
            BankSlot held = slots.get(i);
            emptied.putIfAbsent(held.item().id(), (long) held.amount());
        }
        state.bankOps().bankEmptied(emptied);
    }

    private void handleShopOpen(ByteBuf body) {
        if (body.readableBytes() < 5) {
            return;
        }
        int shopSize = body.readUnsignedByte();
        boolean generalStore = body.readUnsignedByte() != 0;
        int sellModifier = body.readUnsignedByte();
        int buyModifier = body.readUnsignedByte();
        int stockSensitivity = body.readUnsignedByte();
        state.clearShopStock();
        state.setShopHeader(generalStore, sellModifier, buyModifier, stockSensitivity);
        for (int i = 0; i < shopSize && body.readableBytes() >= 6; i++) {
            int catalogId = body.readUnsignedShort();
            int amount = body.readUnsignedShort();
            int baseAmount = body.readUnsignedShort();
            state.setShopStock(catalogId, amount, baseAmount);
        }
        state.setShopScreenOpen(true);
        log.info("[{}] shop opened ({} items)", accountName, shopSize);
    }

    private void handleFatigue(ByteBuf body) {
        if (body.readableBytes() < 4) {
            return;
        }
        int percentShort = body.readUnsignedShort();
        int authentic = body.readUnsignedShort();
        state.setFatigue(percentShort, authentic);
    }

    private void handleSleepFatigue(ByteBuf body) {
        if (body.readableBytes() < 4) {
            return;
        }
        int percentShort = body.readUnsignedShort();
        int authentic = body.readUnsignedShort();
        state.setSleepingFatigue(percentShort, authentic);
        state.noteSleepFatiguePush(state.serverTick());
    }

    private void handleSleepScreen(ByteBuf body) {
        boolean resleep = state.sleeping();
        state.setSleeping(true);
        byte[] payload = new byte[body.readableBytes()];
        body.readBytes(payload);
        state.setPendingCaptcha(payload, state.serverTick());
        log.info(
                "[{}] sleep screen received ({} bytes), resleep={}, sleepingFatigue={}",
                accountName,
                payload.length,
                resleep,
                state.sleepingFatigueShort());
    }

    private void handleOptionMenuOpen(ByteBuf body) {
        if (!body.isReadable()) {
            return;
        }
        int count = body.readUnsignedByte();
        List<String> opts = new ArrayList<>(count);
        for (int i = 0; i < count && body.isReadable(); i++) {
            opts.add(readNlString(body));
        }
        state.dialog().menuOpened(opts, state.serverTick());
        rxLog.info(
                "[{} t{}] IN menu-open options={} ordinal={}",
                accountName,
                state.serverTick(),
                opts,
                state.dialogueMenuOrdinal());
    }

    private static String readNlString(ByteBuf body) {
        StringBuilder sb = new StringBuilder(32);
        while (body.isReadable()) {
            int b = body.readUnsignedByte();
            if (b == 0x0A) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    private static final int MSG_TYPE_TRADE = ServerMessage.TYPE_TRADE;

    private void handleServerMessage(ByteBuf body) {
        if (body.readableBytes() < 6) {
            String bare = readTextPayload(body);
            state.noteServerMessage(ServerMessage.plain(bare));
            rxLog.info("[{}] server: {}", accountName, bare);
            maybeAttributeBusyReply(bare);
            return;
        }
        long iconSprite = body.readUnsignedInt();
        int messageType = body.readUnsignedByte();
        int infoContained = body.readUnsignedByte();
        String message = readNlString(body);
        String senderName = null;
        String colour = null;
        if ((infoContained & 1) != 0) {
            senderName = readNlString(body);
            readNlString(body);
        }
        if ((infoContained & 2) != 0) {
            colour = readNlString(body);
        }

        state.noteServerMessage(new ServerMessage(iconSprite, messageType, message, senderName, colour));
        maybeAttributeBusyReply(message);
        if (messageType == MSG_TYPE_TRADE && senderName != null && !senderName.isEmpty()) {
            state.noteTradeRequestFrom(senderName);
            rxLog.info("[{}] server: trade request from {}", accountName, senderName);
        } else {
            rxLog.info("[{}] server: {}", accountName, message);
        }
    }

    private static final long BUSY_ATTRIBUTION_WINDOW_TICKS = 30L;

    private static final String BUSY_REPLY_SUFFIX = "is busy at the moment";

    private void maybeAttributeBusyReply(String msg) {
        if (msg == null || !msg.contains(BUSY_REPLY_SUFFIX)) {
            return;
        }
        int idx = state.lastTalkAttemptNpcIndex();
        if (idx < 0) {
            return;
        }
        long now = state.serverTick();
        if (now - state.lastTalkAttemptTick() > BUSY_ATTRIBUTION_WINDOW_TICKS) {
            return;
        }
        state.noteNpcBusyReply(idx);
        state.noteDialogResolved();
    }

    private static String readTextPayload(ByteBuf body) {
        if (!body.isReadable()) {
            return "";
        }
        int firstByte = body.readUnsignedByte();
        int remaining = body.readableBytes();
        byte[] bytes = new byte[remaining];
        body.readBytes(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8)
                .replace((char) 0x0A, ' ')
                .trim();
        return text.isEmpty() ? "[type=" + firstByte + "]" : text;
    }
}
