package openrsc.bot.core.world;

import it.unimi.dsi.fastutil.ints.IntSet;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.ActionBubble;
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
import openrsc.bot.api.GameState;
import openrsc.bot.api.GroundItem;
import openrsc.bot.api.Hud;
import openrsc.bot.api.MessageBox;
import openrsc.bot.api.Npc;
import openrsc.bot.api.NpcChat;
import openrsc.bot.api.NpcKillCredit;
import openrsc.bot.api.OnlinePlayer;
import openrsc.bot.api.Party;
import openrsc.bot.api.Player;
import openrsc.bot.api.PlayerChat;
import openrsc.bot.api.PrivateMessage;
import openrsc.bot.api.ServerMessage;
import openrsc.bot.api.TickEvents;
import openrsc.bot.api.UnlockedAppearances;
import openrsc.bot.api.WallObject;
import openrsc.bot.api.WornItem;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.core.codec.Huffman;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.gamedata.defs.ItemDefs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

public final class WorldSnapshotEncoder {

    private static final Logger LOG = LoggerFactory.getLogger(WorldSnapshotEncoder.class);

    private final ItemDefs itemDefs;

    public WorldSnapshotEncoder(ItemDefs itemDefs) {
        this.itemDefs = itemDefs;
    }

    public List<byte[]> initFrames(GameState w, ViewerState vs) {
        List<byte[]> out = new ArrayList<>();
        sendIfChanged(out, vs, "worldinfo", worldInfo(w));
        out.add(privacySettings(w));
        out.add(gameSettings(w));
        out.add(onTutorial(w));
        sendIfChanged(out, vs, "quests", quests(w));
        sendIfChanged(out, vs, "blackhole", blackHole(w));
        sendIfChanged(out, vs, "openpk", openPkPoints(w));
        sendIfChanged(out, vs, "openpkratio", openPkRatio(w));
        sendIfChanged(out, vs, "ironman", ironman(w));
        sendIfChanged(out, vs, "trawler", trawler(w));
        sendIfChanged(out, vs, "onlinelist", onlineList(w));
        sendIfChanged(out, vs, "appearscreen", appearanceScreen(w));
        sendIfChanged(out, vs, "inv", inventory(w));
        if (equipmentTab(w)) {
            sendIfChanged(out, vs, "equip", equipment(w));
        }
        sendIfChanged(out, vs, "stats", stats(w));
        seedStatDeltas(w, vs);
        sendIfChanged(out, vs, "eqstats", equipmentStats(w));
        sendIfChanged(out, vs, "prayers", prayers(w));
        sendIfChanged(out, vs, "fatigue", fatigue(w));
        sendIfChanged(out, vs, "cbstyle", combatStyle(w));
        out.add(playerCoordsFor(w, vs));
        appearanceDelta(out, w, vs);
        out.add(npcCoordsFor(w, vs));
        return out;
    }

    public List<byte[]> snapshotFrames(GameState w) {
        ViewerState vs = new ViewerState();
        List<byte[]> out = new ArrayList<>();
        byte[] configs = serverConfigs(w);
        if (configs != null) {
            out.add(configs);
        }
        out.addAll(initFrames(w, vs));
        out.addAll(deltaFrames(w, vs));
        screenFrames(out, w, vs);
        hudFrames(out, w, vs);
        out.removeIf(f -> f.length > 0 && (f[0] & 0xFF) == Opcode.SEND_QUESTS.wire());
        byte[] compact = questsCompact(w);
        if (compact != null) {
            out.add(compact);
        }
        return out;
    }

    private static byte @Nullable [] serverConfigs(GameState w) {
        Hud h = w.hud();
        PreLoginConfigs cfg = h.serverConfigs();
        if (cfg == null) {
            if (!h.wantBankNotes() && !h.wantDropX()) {
                return null;
            }
            cfg = PreLoginConfigs.wireFlagsOnly(h.wantBankNotes(), h.wantDropX());
        }
        byte[] body = cfg.encode();
        return build(Opcode.SEND_SERVER_CONFIGS, o -> o.write(body));
    }

    public byte[] questsCompact(GameState w) {
        IntSet ids = w.quests().questIds();
        if (ids.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_QUESTS, out -> {
            out.writeByte(0);
            out.writeByte(ids.size());
            for (int id : ids) {
                out.writeInt(id);
                out.writeInt(w.quests().stage(id));
                writeNlString(out, "");
            }
        });
    }

    public List<byte[]> deltaFrames(GameState w, ViewerState vs) {
        int sx = w.pos().x();
        int sz = w.pos().y();
        pruneClearedChunks(w, vs);
        List<byte[]> out = new ArrayList<>(4);
        byte[] scen = sceneryDelta(w, vs, sx, sz);
        if (scen != null) {
            out.add(scen);
        }
        byte[] wall = wallDelta(w, vs, sx, sz);
        if (wall != null) {
            out.add(wall);
        }
        byte[] grnd = groundDelta(w, vs, sx, sz);
        if (grnd != null) {
            out.add(grnd);
        }
        byte[] clear = clearLocations(w, sx, sz);
        if (clear != null) {
            out.add(clear);
        }
        return out;
    }

    private void pruneClearedChunks(GameState w, ViewerState vs) {
        List<Long> chunks = w.tickEvents().clearedChunks();
        if (chunks.isEmpty()) {
            return;
        }
        Set<Long> set = new HashSet<>(chunks);
        vs.scenery.keySet().removeIf(k -> set.contains(ViewerState.chunkOfTileKey(k)));
        vs.walls.keySet().removeIf(k -> set.contains(ViewerState.chunkOfWallKey(k)));
        vs.ground.keySet().removeIf(k -> set.contains(ViewerState.chunkOfGroundKey(k)));
    }

    private byte[] clearLocations(GameState w, int sx, int sz) {
        List<Long> chunks = w.tickEvents().clearedChunks();
        if (chunks.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_REMOVE_WORLD_ENTITY, out -> {
            for (long k : chunks) {
                out.writeShort((TickEvents.clearedChunkX(k) << 3) - sx);
                out.writeShort((TickEvents.clearedChunkZ(k) << 3) - sz);
            }
        });
    }

    private static boolean inByte(int v) {
        return v >= -128 && v <= 127;
    }

    private byte[] sceneryDelta(GameState w, ViewerState vs, int sx, int sz) {
        Map<Long, GameObject> cur = new HashMap<>();
        for (GameObject o : w.scenery().inView()) {
            cur.put(ViewerState.tileKey(o.x(), o.z()), o);
        }
        List<int[]> entries = new ArrayList<>();
        for (Map.Entry<Long, GameObject> e : cur.entrySet()) {
            GameObject o = e.getValue();
            if (!o.equals(vs.scenery.get(e.getKey())) && inByte(o.x() - sx) && inByte(o.z() - sz)) {
                entries.add(new int[] {o.id(), o.x() - sx, o.z() - sz, o.dir()});
            }
        }
        for (Map.Entry<Long, GameObject> e : vs.scenery.entrySet()) {
            GameObject o = e.getValue();
            if (!cur.containsKey(e.getKey()) && inByte(o.x() - sx) && inByte(o.z() - sz)) {
                entries.add(new int[] {60000, o.x() - sx, o.z() - sz, 0});
            }
        }
        vs.scenery.clear();
        vs.scenery.putAll(cur);
        if (entries.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_SCENERY, out -> {
            for (int[] en : entries) {
                out.writeShort(en[0]);
                out.writeByte(en[1]);
                out.writeByte(en[2]);
                out.writeByte(en[3]);
            }
        });
    }

    private byte[] wallDelta(GameState w, ViewerState vs, int sx, int sz) {
        Map<Long, WallObject> cur = new HashMap<>();
        for (WallObject o : w.walls().inView()) {
            cur.put(ViewerState.wallKey(o.x(), o.z(), o.dir()), o);
        }
        List<int[]> entries = new ArrayList<>();
        for (Map.Entry<Long, WallObject> e : cur.entrySet()) {
            WallObject o = e.getValue();
            if (!o.equals(vs.walls.get(e.getKey())) && inByte(o.x() - sx) && inByte(o.z() - sz)) {
                entries.add(new int[] {o.id(), o.x() - sx, o.z() - sz, o.dir()});
            }
        }
        for (Map.Entry<Long, WallObject> e : vs.walls.entrySet()) {
            WallObject o = e.getValue();
            if (!cur.containsKey(e.getKey()) && inByte(o.x() - sx) && inByte(o.z() - sz)) {
                entries.add(new int[] {60000, o.x() - sx, o.z() - sz, o.dir()});
            }
        }
        vs.walls.clear();
        vs.walls.putAll(cur);
        if (entries.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_WALL_OBJECTS, out -> {
            for (int[] en : entries) {
                out.writeShort(en[0]);
                out.writeByte(en[1]);
                out.writeByte(en[2]);
                out.writeByte(en[3]);
            }
        });
    }

    private byte[] groundDelta(GameState w, ViewerState vs, int sx, int sz) {
        boolean notes = w.hud().wantBankNotes();
        Map<Long, Integer> cur = new HashMap<>();
        Map<Long, Integer> notedFlag = new HashMap<>();
        for (GroundItem gi : w.groundItems()) {
            long key = ViewerState.groundKey(gi.x(), gi.z(), gi.id());
            cur.merge(key, 1, Integer::sum);
            if (gi.noted()) {
                notedFlag.put(key, 1);
            }
        }
        List<int[]> entries = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : cur.entrySet()) {
            long key = e.getKey();
            int diff = e.getValue() - vs.ground.getOrDefault(key, 0);
            int dx = ViewerState.groundKeyX(key) - sx;
            int dz = ViewerState.groundKeyZ(key) - sz;
            if (diff > 0 && inByte(dx) && inByte(dz)) {
                int id = ViewerState.groundKeyId(key) & 0x7FFF;
                int nf = notedFlag.getOrDefault(key, 0);
                for (int i = 0; i < diff; i++) {
                    entries.add(new int[] {id, dx, dz, nf});
                }
            }
        }
        for (Map.Entry<Long, Integer> e : vs.ground.entrySet()) {
            long key = e.getKey();
            int diff = e.getValue() - cur.getOrDefault(key, 0);
            int dx = ViewerState.groundKeyX(key) - sx;
            int dz = ViewerState.groundKeyZ(key) - sz;
            if (diff > 0 && inByte(dx) && inByte(dz)) {
                int rawId = (ViewerState.groundKeyId(key) & 0x7FFF) | 0x8000;
                for (int i = 0; i < diff; i++) {
                    entries.add(new int[] {rawId, dx, dz, 0});
                }
            }
        }
        vs.ground.clear();
        vs.ground.putAll(cur);
        if (entries.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_GROUND_ITEMS, out -> {
            for (int[] en : entries) {
                out.writeShort(en[0]);
                out.writeByte(en[1]);
                out.writeByte(en[2]);
                if (notes) {
                    out.writeByte(en[3]);
                }
            }
        });
    }

    public byte[] privacySettings(GameState w) {
        Hud h = w.hud();
        return build(Opcode.SEND_PRIVACY_SETTINGS, out -> {
            out.writeByte(h.privacyBlockChat());
            out.writeByte(h.privacyBlockPrivate());
            out.writeByte(h.privacyBlockTrade());
            out.writeByte(h.privacyBlockDuel());
        });
    }

    public byte[] gameSettings(GameState w) {
        Hud h = w.hud();
        return build(Opcode.SEND_GAME_SETTINGS, out -> {
            out.writeByte(h.gameCameraAuto());
            out.writeByte(h.gameMouseButtonOne());
            out.writeByte(h.gameSoundDisabled());
            for (int opt : h.gameCustomOptions()) {
                out.writeByte(opt);
            }
        });
    }

    public byte[] onTutorial(GameState w) {
        return build(Opcode.SEND_ON_TUTORIAL, out -> out.writeByte(w.onTutorial() ? 1 : 0));
    }

    public byte[] quests(GameState w) {
        IntSet ids = w.quests().questIds();
        if (ids.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_QUESTS, out -> {
            out.writeByte(0);
            out.writeByte(ids.size());
            for (int id : ids) {
                out.writeInt(id);
                out.writeInt(w.quests().stage(id));
                writeNlString(out, questName(id));
            }
        });
    }

    static String questName(int id) {
        QuestId q = QuestId.getById(id);
        return q == null ? "" : q.displayName();
    }

    public List<byte[]> tickFrames(GameState w, ViewerState vs) {
        List<byte[]> out = new ArrayList<>();
        sendIfChanged(out, vs, "worldinfo", worldInfo(w));
        out.add(playerCoordsFor(w, vs));
        out.add(npcCoordsFor(w, vs));
        appearanceDelta(out, w, vs);
        byte[] npcAppr = npcAppearances(w);
        if (npcAppr != null) {
            sendIfChanged(out, vs, "npcappr", npcAppr);
        }
        sendIfChanged(out, vs, "inv", inventory(w));
        if (equipmentTab(w)) {
            sendIfChanged(out, vs, "equip", equipment(w));
        }
        statDeltas(out, w, vs);
        questPointDelta(out, w, vs);
        sendIfChanged(out, vs, "eqstats", equipmentStats(w));
        sendIfChanged(out, vs, "prayers", prayers(w));
        sendIfChanged(out, vs, "fatigue", fatigue(w));
        sendIfChanged(out, vs, "cbstyle", combatStyle(w));
        sendIfChanged(out, vs, "quests", quests(w));
        sendIfChanged(out, vs, "blackhole", blackHole(w));
        for (KillAnnouncement k : w.tickEvents().killAnnouncements()) {
            out.add(killAnnouncement(k));
        }
        sendIfChanged(out, vs, "openpk", openPkPoints(w));
        sendIfChanged(out, vs, "openpkratio", openPkRatio(w));
        sendIfChanged(out, vs, "ironman", ironman(w));
        sendIfChanged(out, vs, "trawler", trawler(w));
        sendIfChanged(out, vs, "onlinelist", onlineList(w));
        sendIfChanged(out, vs, "appearscreen", appearanceScreen(w));
        transientTickFrames(out, w);
        signalFrames(out, w);
        out.addAll(chatFrames(w));
        screenFrames(out, w, vs);
        auctionFrames(out, w, vs);
        hudFrames(out, w, vs);
        out.addAll(deltaFrames(w, vs));
        out.addAll(renderEventFrames(w));
        killCredits(out, w);
        return out;
    }

    private void killCredits(List<byte[]> out, GameState w) {
        for (NpcKillCredit k : w.tickEvents().killCredits()) {
            out.add(build(Opcode.SEND_NPC_KILLS, o -> {
                o.writeInt(k.totalKills());
                o.writeInt(k.npcId());
                o.writeInt(k.perTypeKills());
            }));
        }
    }

    public byte[] blackHole(GameState w) {
        return build(Opcode.SEND_ON_BLACK_HOLE, o -> o.writeByte(w.features().onBlackHole() ? 1 : 0));
    }

    public byte[] killAnnouncement(KillAnnouncement k) {
        return build(Opcode.SEND_KILL_ANNOUNCEMENT, o -> {
            writeNlString(o, k.victim());
            writeNlString(o, k.attacker());
            o.writeInt((int) k.killType());
        });
    }

    private void signalFrames(List<byte[]> out, GameState w) {
        Set<Opcode> sigs = w.tickEvents().signals();
        if (sigs.isEmpty()) {
            return;
        }
        emitSignal(out, sigs, Opcode.SEND_LOGOUT);
        emitSignal(out, sigs, Opcode.SEND_CANT_LOGOUT);
        emitSignal(out, sigs, Opcode.SEND_LOGOUT_REQUEST_CONFIRM);
        emitSignal(out, sigs, Opcode.SEND_SLEEPWORD_INCORRECT);
        emitSignal(out, sigs, Opcode.SEND_OPEN_DETAILS);
        emitSignal(out, sigs, Opcode.SEND_OPEN_RECOVERY);
    }

    public byte[] appearanceScreen(GameState w) {
        return w.appearanceEditorOpen() ? build(Opcode.SEND_APPEARANCE_SCREEN, o -> {}) : null;
    }

    private void transientTickFrames(List<byte[]> out, GameState w) {
        if (w.appearanceEditorOpen()) {
            out.add(build(Opcode.SEND_APPEARANCE_KEEPALIVE, o -> {}));
        }
    }

    private void emitSignal(List<byte[]> out, Set<Opcode> sigs, Opcode opcode) {
        if (sigs.contains(opcode)) {
            out.add(build(opcode, o -> {}));
        }
    }

    public byte[] ironman(GameState w) {
        IronmanHud h = w.features().ironman();
        if (h == null) {
            return null;
        }
        return build(Opcode.SEND_IRONMAN, o -> {
            o.writeByte(h.interfaceId());
            o.writeByte(h.actionId());
            if (h.actionId() == 0) {
                o.writeByte(h.type());
                o.writeByte(h.restriction());
            }
        });
    }

    public byte[] trawler(GameState w) {
        TrawlerHud t = w.features().trawler();
        if (t == null) {
            return null;
        }
        return build(Opcode.SEND_FISHING_TRAWLER, o -> {
            o.writeByte(t.interfaceId());
            o.writeByte(t.actionId());
            if (t.actionId() == 1) {
                o.writeShort(t.waterLevel());
                o.writeShort(t.fishCaught());
                o.writeByte(t.minutesLeft());
                o.writeByte(t.netBroken());
            }
        });
    }

    public byte[] onlineList(GameState w) {
        List<OnlinePlayer> list = w.social().onlineList();
        if (list.isEmpty()) {
            return null;
        }
        return build(Opcode.SEND_ONLINE_LIST, o -> {
            o.writeShort(list.size());
            for (OnlinePlayer p : list) {
                writeNlString(o, p.name());
                o.writeInt((int) p.icon());
                writeNlString(o, p.location());
            }
        });
    }

    public byte[] openPkPoints(GameState w) {
        return w.features().openPkSeen()
                ? build(Opcode.SEND_OPENPK_POINTS, o -> o.writeLong(w.features().openPkPoints()))
                : null;
    }

    public byte[] openPkRatio(GameState w) {
        return w.features().openPkSeen()
                ? build(
                        Opcode.SEND_OPENPK_POINTS_TO_GP_RATIO,
                        o -> o.writeLong(w.features().openPkPointsToGpRatio()))
                : null;
    }

    private void screenFrames(List<byte[]> out, GameState w, ViewerState vs) {
        if (w.bankOpen()) {
            sendIfChanged(out, vs, "bank", bankOpen(w));
        } else if (vs.lastSent.remove("bank") != null) {
            out.add(build(Opcode.SEND_BANK_CLOSE, o -> {}));
        }
        if (w.shop().open()) {
            sendIfChanged(out, vs, "shop", shopOpen(w));
        } else if (vs.lastSent.remove("shop") != null) {
            out.add(build(Opcode.SEND_SHOP_CLOSE, o -> {}));
        }
        if (w.trade().open()) {
            sendIfChanged(out, vs, "trade_win", tradeWindow(w));
            sendIfChanged(out, vs, "trade_items", tradeItems(w));
            sendIfChanged(out, vs, "trade_myacc", acceptFlag(Opcode.SEND_TRADE_ACCEPTED, false));
            sendIfChanged(
                    out,
                    vs,
                    "trade_otheracc",
                    acceptFlag(Opcode.SEND_TRADE_OTHER_ACCEPTED, w.trade().otherAccepted()));
            if (w.trade().confirmOpen()) {
                sendIfChanged(out, vs, "trade_confirm", tradeConfirm(w));
            }
        } else if (vs.lastSent.remove("trade_win") != null) {
            out.add(build(Opcode.SEND_TRADE_CLOSE, o -> {}));
            vs.lastSent.keySet().removeIf(k -> k.startsWith("trade_"));
        }
        Duel d = w.duel();
        if (d.windowOpen()) {
            boolean notes = w.hud().wantBankNotes();
            sendIfChanged(out, vs, "duel_win", build(Opcode.SEND_DUEL_WINDOW, o -> o.writeShort(d.opponentIndex())));
            sendIfChanged(
                    out,
                    vs,
                    "duel_ostake",
                    build(Opcode.SEND_DUEL_OPPONENTS_ITEMS, o -> writeStake(o, d.opponentStake(), notes)));
            sendIfChanged(out, vs, "duel_settings", duelSettings(d));
            sendIfChanged(out, vs, "duel_myacc", acceptFlag(Opcode.SEND_DUEL_ACCEPTED, d.myAccepted() != 0));
            sendIfChanged(
                    out, vs, "duel_otheracc", acceptFlag(Opcode.SEND_DUEL_OTHER_ACCEPTED, d.otherAccepted() != 0));
            if (d.confirmOpen()) {
                sendIfChanged(out, vs, "duel_confirm", duelConfirm(d, notes));
            }
        } else if (vs.lastSent.remove("duel_win") != null) {
            out.add(build(Opcode.SEND_DUEL_CLOSE, o -> {}));
            vs.lastSent.keySet().removeIf(k -> k.startsWith("duel_"));
        }
        List<String> opts = w.dialog().options();
        if (!opts.isEmpty()) {
            sendIfChanged(out, vs, "optmenu", build(Opcode.SEND_OPTION_MENU_OPEN, o -> {
                o.writeByte(opts.size());
                for (String s : opts) {
                    o.write(s.getBytes(StandardCharsets.US_ASCII));
                    o.writeByte('\n');
                }
            }));
        } else if (vs.lastSent.remove("optmenu") != null) {
            out.add(build(Opcode.SEND_OPTION_MENU_CLOSE, o -> {}));
        }
        if (w.fatigue().sleeping()) {
            byte[] captcha = w.features().pendingCaptcha();
            sendIfChanged(out, vs, "sleep", build(Opcode.SEND_SLEEPSCREEN, o -> {
                if (captcha != null) {
                    o.write(captcha);
                }
            }));
            sendIfChanged(out, vs, "sleepfat", build(Opcode.SEND_SLEEP_FATIGUE, o -> {
                o.writeShort(w.fatigue().sleepingFatiguePercent());
                o.writeShort(w.fatigue().sleepingFatigueShort());
            }));
        } else if (vs.lastSent.remove("sleep") != null) {
            out.add(build(Opcode.SEND_STOPSLEEP, o -> {}));
            vs.lastSent.remove("sleepfat");
        }
    }

    private void hudFrames(List<byte[]> out, GameState w, ViewerState vs) {
        Hud h = w.hud();
        if (h.systemUpdateTimer() > 0) {
            sendIfChanged(
                    out, vs, "sysupd", build(Opcode.SEND_SYSTEM_UPDATE, o -> o.writeShort(h.systemUpdateTimer())));
        } else if (vs.lastSent.remove("sysupd") != null) {
            out.add(build(Opcode.SEND_SYSTEM_UPDATE, o -> o.writeShort(0)));
        }
        if (h.elixirTimer() > 0) {
            sendIfChanged(out, vs, "elixir", build(Opcode.SEND_ELIXIR, o -> o.writeShort(h.elixirTimer())));
        } else if (vs.lastSent.remove("elixir") != null) {
            out.add(build(Opcode.SEND_ELIXIR, o -> o.writeShort(0)));
        }
        if (h.experienceFrozen() != 0) {
            sendIfChanged(
                    out, vs, "xptoggle", build(Opcode.SEND_EXPERIENCE_TOGGLE, o -> o.writeByte(h.experienceFrozen())));
        } else if (vs.lastSent.remove("xptoggle") != null) {
            out.add(build(Opcode.SEND_EXPERIENCE_TOGGLE, o -> o.writeByte(0)));
        }
        if (h.experienceShared() != 0) {
            sendIfChanged(out, vs, "xpshared", build(Opcode.SEND_EXPSHARED, o -> o.writeShort(h.experienceShared())));
        } else if (vs.lastSent.remove("xpshared") != null) {
            out.add(build(Opcode.SEND_EXPSHARED, o -> o.writeShort(0)));
        }
        if (h.bankPinOpen()) {
            sendIfChanged(out, vs, "bankpin", build(Opcode.SEND_BANK_PIN_INTERFACE, o -> o.writeByte(1)));
        } else if (vs.lastSent.remove("bankpin") != null) {
            out.add(build(Opcode.SEND_BANK_PIN_INTERFACE, o -> o.writeByte(0)));
        }
        if (h.inputBoxPrompt() != null) {
            String prompt = h.inputBoxPrompt();
            sendIfChanged(out, vs, "inputbox", build(Opcode.SEND_INPUT_BOX, o -> writeNlString(o, prompt)));
        } else {
            vs.lastSent.remove("inputbox");
        }
        if (h.progressInterface() > 0) {
            sendIfChanged(out, vs, "progress", build(Opcode.SEND_STATUS_PROGRESS_BAR, o -> {
                o.writeByte(h.progressInterface());
                if (h.progressInterface() != 2) {
                    if (h.progressInterface() == 1) {
                        o.writeShort(h.progressDelay());
                    }
                    o.writeByte(h.progressRepeat());
                }
            }));
        } else if (vs.lastSent.remove("progress") != null) {
            out.add(build(Opcode.SEND_STATUS_PROGRESS_BAR, o -> o.writeByte(0)));
        }
        for (FriendEntry f : w.social().friends()) {
            sendIfChanged(out, vs, "friend:" + f.name(), build(Opcode.SEND_FRIEND_UPDATE, o -> {
                writeNlString(o, f.name());
                writeNlString(o, f.formerName() == null ? "" : f.formerName());
                o.writeByte(f.onlineStatus());
                if (f.world() != null && !f.world().isEmpty()) {
                    writeNlString(o, f.world());
                }
            }));
        }
        if (!w.social().ignoreList().isEmpty()) {
            sendIfChanged(out, vs, "ignore", ignoreList(w));
        } else if (vs.lastSent.remove("ignore") != null) {
            out.add(ignoreList(w));
        }
        Clan c = w.clan();
        if (c.inClan()) {
            sendIfChanged(out, vs, "clan", build(Opcode.SEND_CLAN, o -> {
                o.writeByte(0);
                writeNlString(o, c.clanName() == null ? "" : c.clanName());
                writeNlString(o, c.clanTag() == null ? "" : c.clanTag());
                writeNlString(o, c.leaderName() == null ? "" : c.leaderName());
                o.writeByte(c.isLeader() ? 1 : 0);
                o.writeByte(c.members().size());
                for (Clan.Member m : c.members()) {
                    writeNlString(o, m.name());
                    o.writeByte(m.rank());
                    o.writeByte(m.online() ? 1 : 0);
                }
            }));
        } else if (vs.lastSent.remove("clan") != null) {
            out.add(build(Opcode.SEND_CLAN, o -> o.writeByte(1)));
        }
        Party pt = w.party();
        if (pt.inParty()) {
            sendIfChanged(out, vs, "party", build(Opcode.SEND_PARTY, o -> {
                o.writeByte(0);
                writeNlString(o, pt.leaderName() == null ? "" : pt.leaderName());
                o.writeByte(pt.isLeader() ? 1 : 0);
                o.writeByte(pt.members().size());
                for (Party.Member m : pt.members()) {
                    writeNlString(o, m.name());
                    o.writeByte(m.rank());
                    o.writeByte(m.online() ? 1 : 0);
                    o.writeByte(m.currentHits());
                    o.writeByte(m.maxHits());
                    o.writeByte(m.combatLevel());
                    o.writeByte(m.skulled() ? 1 : 0);
                    o.writeByte(m.dead() ? 1 : 0);
                    o.writeByte(m.shareLoot() ? 1 : 0);
                    o.writeByte(m.totalLevel());
                    o.writeByte(m.inCombat() ? 1 : 0);
                    o.writeByte(m.shareExp());
                    o.writeLong(m.shareExpAccrued());
                }
            }));
        } else if (vs.lastSent.remove("party") != null) {
            out.add(build(Opcode.SEND_PARTY, o -> o.writeByte(1)));
        }
        UnlockedAppearances ua = w.selfPanel().unlockedAppearances();
        if (ua != null) {
            sendIfChanged(out, vs, "unlocked", unlockedFrame(ua));
        }
        for (BankPreset preset : w.features().bankPresets()) {
            sendIfChanged(out, vs, "preset:" + preset.slot(), bankPresetFrame(preset));
        }
    }

    private static byte[] unlockedFrame(UnlockedAppearances ua) {
        boolean[][] arrs = {
            ua.hairStyles(), ua.bodyTypes(), ua.skinColours(), ua.hairColours(), ua.topColours(), ua.bottomColours()
        };
        return build(Opcode.SEND_UNLOCKED_APPEARANCES, o -> {
            int totalBits = 0;
            for (boolean[] a : arrs) {
                o.writeInt(a.length);
                totalBits += a.length;
            }
            byte[] bits = new byte[(totalBits + 7) / 8];
            int bit = 0;
            for (boolean[] a : arrs) {
                for (boolean v : a) {
                    if (v) {
                        bits[bit >> 3] |= (byte) (1 << (7 - (bit & 7)));
                    }
                    bit++;
                }
            }
            o.write(bits);
        });
    }

    private byte[] bankPresetFrame(BankPreset preset) {
        return build(Opcode.SEND_BANK_PRESET, o -> {
            o.writeShort(preset.slot());
            writePresetSlots(o, preset.inventory(), 30, true);
            writePresetSlots(o, preset.equipment(), 14, false);
        });
    }

    private void writePresetSlots(DataOutputStream o, List<InventorySlot> items, int count, boolean hasNoted)
            throws IOException {
        InventorySlot[] bySlot = new InventorySlot[count];
        for (InventorySlot it : items) {
            if (it.slot() >= 0 && it.slot() < count) {
                bySlot[it.slot()] = it;
            }
        }
        for (int i = 0; i < count; i++) {
            InventorySlot it = bySlot[i];
            if (it == null) {
                o.writeByte(0xFF);
                continue;
            }
            o.writeShort(it.id());
            if (hasNoted) {
                o.writeByte(it.noted() ? 1 : 0);
            }
            if (it.noted() || (itemDefs != null && itemDefs.isStackable(it.id()))) {
                o.writeInt(it.amount());
            }
        }
    }

    private byte[] bankOpen(GameState w) {
        Bank b = w.bank();
        List<BankSlot> slots = b.slots();
        int maxSize = b.capacity();
        return build(Opcode.SEND_BANK_OPEN, o -> {
            o.writeShort(slots.size());
            o.writeShort(maxSize);
            for (BankSlot it : slots) {
                o.writeShort(it.id());
                o.writeInt(it.amount());
            }
        });
    }

    private void auctionFrames(List<byte[]> out, GameState w, ViewerState vs) {
        if (!w.auction().open()) {
            vs.auctionSent = null;
            return;
        }
        if (w.auction().lastChunkAt() == null) {
            return;
        }
        List<AuctionHouse.Listing> listings = w.auction().listings();
        if (listings == vs.auctionSent || listings.equals(vs.auctionSent)) {
            vs.auctionSent = listings;
            return;
        }
        vs.auctionSent = listings;
        out.add(build(Opcode.SEND_AUCTION_LIST, o -> o.writeByte(0)));
        for (int from = 0; from < listings.size() || from == 0; from += AuctionHouse.CHUNK) {
            out.add(auctionChunk(listings.subList(from, Math.min(listings.size(), from + AuctionHouse.CHUNK))));
        }
    }

    private static byte[] auctionChunk(List<AuctionHouse.Listing> listings) {
        return build(Opcode.SEND_AUCTION_LIST, o -> {
            o.writeByte(1);
            o.writeShort(listings.size());
            for (AuctionHouse.Listing l : listings) {
                o.writeInt(l.auctionId());
                o.writeInt(l.itemId());
                o.writeInt(l.amountLeft());
                o.writeInt(l.price());
                o.writeByte(l.mine() ? 1 : 0);
                if (!l.mine()) {
                    writeNlString(o, l.seller());
                }
                o.writeByte(l.hoursLeft());
            }
        });
    }

    private byte[] shopOpen(GameState w) {
        List<Integer> catalog = new ArrayList<>(w.shop().catalog());
        return build(Opcode.SEND_SHOP_OPEN, o -> {
            o.writeByte(catalog.size());
            o.writeByte(w.shop().generalStore() ? 1 : 0);
            o.writeByte(w.shop().sellModifier());
            o.writeByte(w.shop().buyModifier());
            o.writeByte(w.shop().stockSensitivity());
            for (int id : catalog) {
                o.writeShort(id);
                o.writeShort(w.shop().amount(id));
                o.writeShort(w.shop().baseAmount(id));
            }
        });
    }

    private byte[] tradeWindow(GameState w) {
        return build(Opcode.SEND_TRADE_WINDOW, o -> o.writeShort(w.trade().withServerIndex()));
    }

    private byte[] tradeItems(GameState w) {
        boolean notes = w.hud().wantBankNotes();
        return build(Opcode.SEND_TRADE_OTHER_ITEMS, o -> {
            writeOffer(o, w.trade().otherOffer(), notes);
            writeOffer(o, w.trade().myOffer(), notes);
        });
    }

    private byte[] tradeConfirm(GameState w) {
        String name = opponentName(w, w.trade().withServerIndex());
        boolean notes = w.hud().wantBankNotes();
        return build(Opcode.SEND_TRADE_OPEN_CONFIRM, o -> {
            writeNlString(o, name);
            writeOffer(o, w.trade().otherOffer(), notes);
            writeOffer(o, w.trade().myOffer(), notes);
        });
    }

    private static byte[] duelSettings(Duel d) {
        return build(Opcode.SEND_DUEL_SETTINGS, o -> {
            o.writeByte(d.disallowRetreat());
            o.writeByte(d.disallowMagic());
            o.writeByte(d.disallowPrayer());
            o.writeByte(d.disallowWeapons());
        });
    }

    private static byte[] duelConfirm(Duel d, boolean notes) {
        return build(Opcode.SEND_DUEL_CONFIRMWINDOW, o -> {
            writeNlString(o, d.confirmOpponentName() == null ? "" : d.confirmOpponentName());
            writeStake(o, d.opponentStake(), notes);
            writeStake(o, d.myStake(), notes);
            o.writeByte(d.disallowRetreat());
            o.writeByte(d.disallowMagic());
            o.writeByte(d.disallowPrayer());
            o.writeByte(d.disallowWeapons());
        });
    }

    private static byte[] acceptFlag(Opcode opcode, boolean accepted) {
        return build(opcode, o -> o.writeByte(accepted ? 1 : 0));
    }

    private static void writeOffer(DataOutputStream o, List<InventorySlot> items, boolean notes) throws IOException {
        List<InventorySlot> list = items == null ? List.of() : items;
        o.writeByte(list.size());
        for (InventorySlot it : list) {
            o.writeShort(it.id());
            if (notes) {
                o.writeByte(it.noted() ? 1 : 0);
            }
            o.writeInt(it.amount());
        }
    }

    private static void writeStake(DataOutputStream o, List<Duel.StakeItem> items, boolean notes) throws IOException {
        List<Duel.StakeItem> list = items == null ? List.of() : items;
        o.writeByte(list.size());
        for (Duel.StakeItem it : list) {
            o.writeShort(it.catalogId());
            if (notes) {
                o.writeByte(it.noted() ? 1 : 0);
            }
            o.writeInt(it.amount());
        }
    }

    private static void writeNlString(DataOutputStream o, String s) throws IOException {
        o.write((s == null ? "" : s).getBytes(StandardCharsets.US_ASCII));
        o.writeByte('\n');
    }

    private static String opponentName(GameState w, int serverIndex) {
        Player p = w.playerByServerIndex(serverIndex);
        return p != null && p.username() != null ? p.username() : "";
    }

    private static void sendIfChanged(List<byte[]> out, ViewerState vs, String key, byte[] body) {
        if (body == null) {
            return;
        }
        byte[] last = vs.lastSent.get(key);
        if (last == null || !Arrays.equals(last, body)) {
            vs.lastSent.put(key, body);
            out.add(body);
        }
    }

    public List<byte[]> renderEventFrames(GameState w) {
        List<RenderEvent> events = w.tickEvents().renderEvents();
        if (events.isEmpty()) {
            return List.of();
        }
        record Entry(int index, int type, byte[] payload) {}
        List<Entry> playerEntries = new ArrayList<>();
        List<Entry> npcEntries = new ArrayList<>();
        List<byte[]> out = new ArrayList<>();
        for (RenderEvent e : events) {
            switch (e) {
                case RenderEvent.DamageSplat s -> {
                    int[] hp = hitsFor(w, s.targetIndex(), s.targetIsPlayer());
                    byte[] payload = {(byte) s.damage(), (byte) hp[0], (byte) hp[1]};
                    (s.targetIsPlayer() ? playerEntries : npcEntries).add(new Entry(s.targetIndex(), 2, payload));
                }
                case RenderEvent.Projectile p -> {
                    byte[] payload = {
                        (byte) (p.spriteType() >> 8), (byte) p.spriteType(),
                        (byte) (p.victimIndex() >> 8), (byte) p.victimIndex()
                    };
                    int type = p.victimIsPlayer() ? 4 : 3;
                    (p.casterIsNpc() ? npcEntries : playerEntries).add(new Entry(p.casterIndex(), type, payload));
                }
                case RenderEvent.TeleBubble b ->
                    out.add(build(Opcode.SEND_BUBBLE, o -> {
                        o.writeByte(b.isGrab());
                        o.writeByte(b.localX());
                        o.writeByte(b.localZ());
                    }));
                case RenderEvent.Sound s ->
                    out.add(build(Opcode.SEND_PLAY_SOUND, o -> {
                        o.write(s.name().getBytes(StandardCharsets.US_ASCII));
                        o.writeByte('\n');
                    }));
                default -> {}
            }
        }
        if (!playerEntries.isEmpty()) {
            out.add(build(Opcode.SEND_UPDATE_PLAYERS, o -> {
                o.writeShort(playerEntries.size());
                for (Entry en : playerEntries) {
                    o.writeShort(en.index());
                    o.writeByte(en.type());
                    o.write(en.payload());
                }
            }));
        }
        if (!npcEntries.isEmpty()) {
            out.add(build(Opcode.SEND_UPDATE_NPC, o -> {
                o.writeShort(npcEntries.size());
                for (Entry en : npcEntries) {
                    o.writeShort(en.index());
                    o.writeByte(en.type());
                    o.write(en.payload());
                }
            }));
        }
        return out;
    }

    private record UpdateEntry(int index, int type, byte[] payload) {}

    private static UpdateEntry chatEntry(PlayerChat c) {
        byte[] text = c.text().getBytes(StandardCharsets.US_ASCII);
        byte[] p;
        if (c.type() == 6) {
            p = new byte[text.length + 1];
            System.arraycopy(text, 0, p, 0, text.length);
        } else if (c.type() == 7) {
            p = new byte[4 + 2 + text.length + 1];
            writeIntBE(p, 0, c.icon());
            p[4] = (byte) (c.muted() ? 1 : 0);
            p[5] = (byte) (c.tutorial() ? 1 : 0);
            System.arraycopy(text, 0, p, 6, text.length);
        } else {
            p = new byte[4 + text.length + 1];
            writeIntBE(p, 0, c.icon());
            System.arraycopy(text, 0, p, 4, text.length);
        }
        p[p.length - 1] = '\n';
        return new UpdateEntry(c.senderIndex(), c.type(), p);
    }

    private byte[] npcAppearances(GameState w) {
        List<UpdateEntry> entries = new ArrayList<>();
        for (Npc n : w.npcs()) {
            if (n.skullType() != 0) {
                entries.add(new UpdateEntry(n.serverIndex(), 5, new byte[] {(byte) n.skullType()}));
            }
            if (n.wield1() != 0 || n.wield2() != 0) {
                entries.add(new UpdateEntry(n.serverIndex(), 6, new byte[] {(byte) n.wield1(), (byte) n.wield2()}));
            }
        }
        return entries.isEmpty() ? null : updatePacket(Opcode.SEND_UPDATE_NPC, entries);
    }

    List<byte[]> chatFrames(GameState w) {
        List<UpdateEntry> playerEntries = new ArrayList<>();
        List<UpdateEntry> npcEntries = new ArrayList<>();
        List<byte[]> out = new ArrayList<>();

        for (PlayerChat c : w.tickEvents().playerChat()) {
            playerEntries.add(chatEntry(c));
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "live-view: forward player chat idx={} type={} inView={} text=\"{}\"",
                        c.senderIndex(),
                        c.type(),
                        w.playerByServerIndex(c.senderIndex()) != null,
                        c.text());
            }
        }
        for (PlayerChat c : w.tickEvents().selfChat()) {
            playerEntries.add(chatEntry(c));
        }
        for (NpcChat c : w.tickEvents().npcChat()) {
            byte[] text = c.text().getBytes(StandardCharsets.US_ASCII);
            byte[] p = new byte[2 + text.length + 1];
            p[0] = (byte) (c.recipient() >> 8);
            p[1] = (byte) c.recipient();
            System.arraycopy(text, 0, p, 2, text.length);
            p[p.length - 1] = '\n';
            npcEntries.add(new UpdateEntry(c.npcIndex(), 1, p));
        }
        for (ActionBubble b : w.tickEvents().actionBubbles()) {
            byte[] p = {(byte) (b.itemId() >> 8), (byte) b.itemId()};
            if (b.isPlayer()) {
                playerEntries.add(new UpdateEntry(b.index(), 0, p));
            } else {
                npcEntries.add(new UpdateEntry(b.index(), 7, p));
            }
        }
        if (!playerEntries.isEmpty()) {
            out.add(updatePacket(Opcode.SEND_UPDATE_PLAYERS, playerEntries));
        }
        if (!npcEntries.isEmpty()) {
            out.add(updatePacket(Opcode.SEND_UPDATE_NPC, npcEntries));
        }
        for (ServerMessage m : w.messages().thisTick()) {
            out.add(serverMessageFrame(m));
        }
        for (MessageBox box : w.tickEvents().messageBoxes()) {
            Opcode opcode = box.top() ? Opcode.SEND_BOX : Opcode.SEND_BOX2;
            out.add(build(opcode, o -> o.write(box.text().getBytes(StandardCharsets.US_ASCII))));
        }
        for (PrivateMessage pm : w.tickEvents().privateMessages()) {
            out.add(privateMessageFrame(pm));
        }
        return out;
    }

    private static byte[] updatePacket(Opcode opcode, List<UpdateEntry> entries) {
        return build(opcode, out -> {
            out.writeShort(entries.size());
            for (UpdateEntry e : entries) {
                out.writeShort(e.index());
                out.writeByte(e.type());
                out.write(e.payload());
            }
        });
    }

    public static byte[] serverMessageFrame(ServerMessage m) {
        int info = (m.sender() != null ? 1 : 0) | (m.colour() != null ? 2 : 0);
        return build(Opcode.SEND_SERVER_MESSAGE, out -> {
            out.writeInt((int) m.icon());
            out.writeByte(m.type());
            out.writeByte(info);
            out.write(m.text().getBytes(StandardCharsets.US_ASCII));
            out.writeByte('\n');
            if ((info & 1) != 0) {
                byte[] name = m.sender().getBytes(StandardCharsets.US_ASCII);
                out.write(name);
                out.writeByte('\n');
                out.write(name);
                out.writeByte('\n');
            }
            if ((info & 2) != 0) {
                out.write(m.colour().getBytes(StandardCharsets.US_ASCII));
                out.writeByte('\n');
            }
        });
    }

    private static void writeIntBE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >> 24);
        buf[off + 1] = (byte) (v >> 16);
        buf[off + 2] = (byte) (v >> 8);
        buf[off + 3] = (byte) v;
    }

    private static byte[] privateMessageFrame(PrivateMessage pm) {
        Opcode opcode = pm.sent() ? Opcode.SEND_PRIVATE_MESSAGE_SENT : Opcode.SEND_PRIVATE_MESSAGE;
        return build(opcode, out -> {
            out.write(pm.name().getBytes(StandardCharsets.US_ASCII));
            out.writeByte('\n');
            if (!pm.sent()) {
                out.write((pm.formerName() == null ? "" : pm.formerName()).getBytes(StandardCharsets.US_ASCII));
                out.writeByte('\n');
                out.writeInt((int) pm.icon());
            }
            String msg = pm.message() == null ? "" : pm.message();
            int len = msg.length();
            if (len < 128) {
                out.writeByte(len);
            } else {
                out.writeShort(len + 32768);
            }
            out.write(Huffman.encode(msg));
        });
    }

    private static int[] hitsFor(GameState w, int index, boolean isPlayer) {
        if (isPlayer) {
            if (index == w.selfServerIndex()) {
                return new int[] {w.stats().level(Skill.HITS.id), w.stats().baseLevel(Skill.HITS.id)};
            }
            Player p = w.playerByServerIndex(index);
            return p == null ? new int[] {0, 0} : new int[] {p.currentHits(), p.maxHits()};
        }
        Npc n = w.npcByServerIndex(index);
        return n == null ? new int[] {0, 0} : new int[] {n.currentHits(), n.maxHits()};
    }

    public byte[] worldInfo(GameState w) {
        return build(Opcode.SEND_WORLD_INFO, out -> {
            out.writeShort(w.selfServerIndex());
            out.writeShort(w.region().worldX());
            out.writeShort(w.region().worldZ());
            out.writeShort(w.region().planeIndex());
            out.writeShort(w.region().planeMultiplier());
        });
    }

    public byte[] fatigue(GameState w) {
        return build(Opcode.SEND_FATIGUE, out -> {
            out.writeShort(w.fatigue().fatiguePercentShort());
            out.writeShort(w.fatigue().fatigueShort());
        });
    }

    public byte[] inventory(GameState w) {
        List<InventorySlot> inv = w.pack().slots();
        return build(Opcode.SEND_INVENTORY, out -> {
            out.writeByte(inv.size());
            for (InventorySlot it : inv) {
                out.writeShort(it.id());
                out.writeByte(it.equipped() ? 1 : 0);
                out.writeByte(it.noted() ? 1 : 0);
                if (stackable(it.id()) || it.noted()) {
                    out.writeInt(it.amount());
                }
            }
        });
    }

    /** The ignore list, empty or not, as the server sends it last of a login. */
    public byte[] ignoreList(GameState w) {
        List<String> ignore = w.social().ignoreList();
        return build(Opcode.SEND_IGNORE_LIST, o -> {
            o.writeByte(ignore.size());
            for (String name : ignore) {
                writeNlString(o, name);
                writeNlString(o, name);
                writeNlString(o, "");
                writeNlString(o, "");
            }
        });
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1057-L1058
    private static boolean equipmentTab(GameState w) {
        PreLoginConfigs configs = w.hud().serverConfigs();
        return configs != null
                ? configs.wantEquipmentTab()
                : w.serverData().world().rules().equipmentTab();
    }

    public byte[] equipment(GameState w) {
        List<WornItem> eq = w.selfPanel().equipment();
        return build(Opcode.SEND_EQUIPMENT, out -> {
            out.writeByte(eq.size());
            for (WornItem it : eq) {
                out.writeByte(it.wieldPos());
                out.writeShort(it.catalogId());
                if (stackable(it.catalogId())) {
                    out.writeInt(it.amount());
                }
            }
        });
    }

    public byte[] stats(GameState w) {
        int n = w.stats().serverSkillCount();
        return build(Opcode.SEND_STATS, out -> {
            for (int i = 0; i < n; i++) {
                out.writeByte(w.stats().level(i) & 0xFF);
            }
            for (int i = 0; i < n; i++) {
                out.writeByte(w.stats().baseLevel(i) & 0xFF);
            }
            for (int i = 0; i < n; i++) {
                out.writeInt((int) w.stats().xp(i));
            }
            out.writeByte(w.quests().questPoints() & 0xFF);
        });
    }

    private void seedStatDeltas(GameState w, ViewerState vs) {
        int n = w.stats().serverSkillCount();
        vs.lastSkillCur = new int[n];
        vs.lastSkillBase = new int[n];
        vs.lastSkillXp = new long[n];
        for (int i = 0; i < n; i++) {
            vs.lastSkillCur[i] = w.stats().level(i);
            vs.lastSkillBase[i] = w.stats().baseLevel(i);
            vs.lastSkillXp[i] = w.stats().xp(i);
        }
        vs.lastQuestPoints = w.quests().questPoints();
    }

    private void questPointDelta(List<byte[]> out, GameState w, ViewerState vs) {
        if (vs.lastQuestPoints == w.quests().questPoints()) {
            return;
        }
        sendIfChanged(out, vs, "stats", stats(w));
        seedStatDeltas(w, vs);
    }

    private void statDeltas(List<byte[]> out, GameState w, ViewerState vs) {
        if (vs.lastSkillCur == null) {
            seedStatDeltas(w, vs);
            return;
        }
        int n = Math.min(w.stats().serverSkillCount(), vs.lastSkillCur.length);
        for (int i = 0; i < n; i++) {
            int cur = w.stats().level(i);
            int base = w.stats().baseLevel(i);
            long xp = w.stats().xp(i);
            if (cur == vs.lastSkillCur[i] && base == vs.lastSkillBase[i] && xp == vs.lastSkillXp[i]) {
                continue;
            }
            int skill = i;
            out.add(build(Opcode.SEND_STAT, o -> {
                o.writeByte(skill);
                o.writeByte(cur & 0xFF);
                o.writeByte(base & 0xFF);
                o.writeInt((int) xp);
            }));
            vs.lastSkillCur[i] = cur;
            vs.lastSkillBase[i] = base;
            vs.lastSkillXp[i] = xp;
        }
    }

    public byte[] equipmentStats(GameState w) {
        int[] s = w.selfPanel().equipmentStats();
        return build(Opcode.SEND_EQUIPMENT_STATS, out -> {
            for (int i = 0; i < 5; i++) {
                out.writeByte(s[i] & 0xFF);
            }
            for (int i = 0; i < 5; i++) {
                out.writeInt(s[i]);
            }
        });
    }

    public byte[] prayers(GameState w) {
        int[] flags = w.selfPanel().prayersActive();
        return build(Opcode.SEND_PRAYERS_ACTIVE, out -> {
            for (int f : flags) {
                out.writeByte(f & 0xFF);
            }
        });
    }

    public byte[] combatStyle(GameState w) {
        return build(Opcode.SEND_COMBAT_STYLE, out -> out.writeByte(w.hud().combatStyle() & 0xFF));
    }

    public byte[] playerCoords(GameState w) {
        return playerCoordsFor(w, new ViewerState());
    }

    public byte[] playerCoordsFor(GameState w, ViewerState vs) {
        int sx = w.pos().x();
        int sz = w.pos().y();
        Map<Integer, Player> cur = new HashMap<>();
        for (Player p : w.players()) {
            cur.put(p.serverIndex(), p);
        }
        BitWriter bw = new BitWriter();
        bw.writeBits(sx, 11);
        bw.writeBits(sz, 13);
        bw.writeBits(w.spriteDir(), 4);
        bw.writeBits(vs.knownPlayers.size(), 8);
        List<Integer> survivors = new ArrayList<>();
        for (int idx : vs.knownPlayers) {
            Player p = cur.get(idx);
            if (p == null) {
                writeRemove(bw);
                vs.playerLast.remove(idx);
            } else if (writeCachedUpdate(bw, p.x(), p.z(), p.sprite(), vs.playerLast.get(idx))) {
                survivors.add(idx);
            } else {
                vs.playerLast.remove(idx);
            }
        }
        List<Integer> fresh = new ArrayList<>();
        for (Player p : w.players()) {
            if (!vs.playerLast.containsKey(p.serverIndex())) {
                bw.writeBits(p.serverIndex(), 11);
                bw.writeBits((p.x() - sx) & 0x3F, 6);
                bw.writeBits((p.z() - sz) & 0x3F, 6);
                bw.writeBits(p.sprite(), 4);
                vs.playerLast.put(p.serverIndex(), new int[] {p.x(), p.z(), p.sprite()});
                fresh.add(p.serverIndex());
            }
        }
        vs.knownPlayers.clear();
        vs.knownPlayers.addAll(survivors);
        vs.knownPlayers.addAll(fresh);
        return prependOpcode(Opcode.SEND_PLAYER_COORDS, bw.toBytes());
    }

    public byte[] npcCoordsFor(GameState w, ViewerState vs) {
        int sx = w.pos().x();
        int sz = w.pos().y();
        Map<Integer, Npc> cur = new HashMap<>();
        for (Npc n : w.npcs()) {
            cur.put(n.serverIndex(), n);
        }
        BitWriter bw = new BitWriter();
        bw.writeBits(vs.knownNpcs.size(), 8);
        List<Integer> survivors = new ArrayList<>();
        for (int idx : vs.knownNpcs) {
            Npc n = cur.get(idx);
            if (n == null) {
                writeRemove(bw);
                vs.npcLast.remove(idx);
            } else if (writeCachedUpdate(bw, n.x(), n.z(), n.sprite(), vs.npcLast.get(idx))) {
                survivors.add(idx);
            } else {
                vs.npcLast.remove(idx);
            }
        }
        List<Integer> fresh = new ArrayList<>();
        for (Npc n : w.npcs()) {
            if (!vs.npcLast.containsKey(n.serverIndex())) {
                bw.writeBits(n.serverIndex(), 12);
                bw.writeBits((n.x() - sx) & 0x3F, 6);
                bw.writeBits((n.z() - sz) & 0x3F, 6);
                bw.writeBits(n.sprite(), 4);
                bw.writeBits(n.id(), 10);
                vs.npcLast.put(n.serverIndex(), new int[] {n.x(), n.z(), n.sprite()});
                fresh.add(n.serverIndex());
            }
        }
        vs.knownNpcs.clear();
        vs.knownNpcs.addAll(survivors);
        vs.knownNpcs.addAll(fresh);
        return prependOpcode(Opcode.SEND_NPC_COORDS, bw.toBytes());
    }

    private static boolean writeCachedUpdate(BitWriter bw, int x, int z, int sprite, int[] last) {
        int dx = x - last[0];
        int dz = z - last[1];
        boolean moved = dx != 0 || dz != 0;
        boolean spriteChanged = sprite != last[2];
        if (!moved && !spriteChanged) {
            bw.writeBits(0, 1);
            return true;
        }
        if (moved) {
            int dir = walkDir(dx, dz);
            if (dir < 0) {
                writeRemove(bw);
                return false;
            }
            bw.writeBits(1, 1);
            bw.writeBits(0, 1);
            bw.writeBits(dir, 3);
            last[0] = x;
            last[1] = z;
            last[2] = dir;
            return true;
        }
        bw.writeBits(1, 1);
        bw.writeBits(1, 1);
        bw.writeBits((sprite >> 2) & 3, 2);
        bw.writeBits(sprite & 3, 2);
        last[2] = sprite;
        return true;
    }

    private static void writeRemove(BitWriter bw) {
        bw.writeBits(1, 1);
        bw.writeBits(1, 1);
        bw.writeBits(3, 2);
    }

    private static int walkDir(int dx, int dz) {
        if (dx == 0 && dz == -1) {
            return 0;
        }
        if (dx == 1 && dz == -1) {
            return 1;
        }
        if (dx == 1 && dz == 0) {
            return 2;
        }
        if (dx == 1 && dz == 1) {
            return 3;
        }
        if (dx == 0 && dz == 1) {
            return 4;
        }
        if (dx == -1 && dz == 1) {
            return 5;
        }
        if (dx == -1 && dz == 0) {
            return 6;
        }
        if (dx == -1 && dz == -1) {
            return 7;
        }
        return -1;
    }

    private record ApprEntry(int index, String username, Appearance a) {}

    private List<ApprEntry> apprEntries(GameState w) {
        List<ApprEntry> entries = new ArrayList<>();
        if (w.selfPanel().appearance().known()) {
            entries.add(new ApprEntry(
                    w.selfServerIndex(), w.username(), w.selfPanel().appearance()));
        }
        for (Player pl : w.players()) {
            Player p = pl;
            if (p.appearance().known()) {
                entries.add(new ApprEntry(p.serverIndex(), p.username() == null ? "" : p.username(), p.appearance()));
            }
        }
        return entries;
    }

    private static void writeApprEntry(DataOutputStream out, ApprEntry e) throws IOException {
        Appearance a = e.a();
        out.writeShort(e.index());
        out.writeByte(5);
        out.write(e.username().getBytes(StandardCharsets.US_ASCII));
        out.writeByte('\n');
        int[] eq = a.equipmentSprites();
        out.writeByte(eq.length);
        for (int sprite : eq) {
            out.writeShort(sprite);
        }
        out.writeByte(a.hairColour());
        out.writeByte(a.topColour());
        out.writeByte(a.trouserColour());
        out.writeByte(a.skinColour());
        out.writeByte(a.combatLevel());
        out.writeByte(a.skullType());
        if (a.clanTag() != null) {
            out.writeByte(1);
            out.write(a.clanTag().getBytes(StandardCharsets.US_ASCII));
            out.writeByte('\n');
        } else {
            out.writeByte(0);
        }
        out.writeByte(a.invisible() ? 1 : 0);
        out.writeByte(a.invulnerable() ? 1 : 0);
        out.writeByte(a.groupId());
        out.writeInt((int) a.icon());
    }

    private static byte[] apprBlock(ApprEntry e) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            writeApprEntry(out, e);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bos.toByteArray();
    }

    private void appearanceDelta(List<byte[]> out, GameState w, ViewerState vs) {
        List<ApprEntry> entries = apprEntries(w);
        List<byte[]> changed = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (ApprEntry e : entries) {
            present.add(e.index());
            byte[] block = apprBlock(e);
            byte[] last = vs.lastAppearance.get(e.index());
            if (last == null || !Arrays.equals(last, block)) {
                changed.add(block);
                vs.lastAppearance.put(e.index(), block);
            }
        }
        vs.lastAppearance.keySet().retainAll(present);
        if (changed.isEmpty()) {
            return;
        }
        out.add(build(Opcode.SEND_UPDATE_PLAYERS, o -> {
            o.writeShort(changed.size());
            for (byte[] b : changed) {
                o.write(b);
            }
        }));
    }

    private boolean stackable(int id) {
        return itemDefs != null && itemDefs.isStackable(id);
    }

    private interface BodyWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public record LiveTick(long session, long tick, long min, long max, boolean live, long liveSession) {}

    public byte[] liveTick(long session, long tick, long minTick, long maxTick, boolean live, long liveSession) {
        String json = JSON.writeValueAsString(new LiveTick(session, tick, minTick, maxTick, live, liveSession));
        return build(Opcode.SEND_LIVE_TICK, out -> writeNlString(out, json));
    }

    private static byte[] build(Opcode opcode, BodyWriter writer) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeByte(opcode.wire());
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] prependOpcode(Opcode opcode, byte[] payload) {
        byte[] body = new byte[payload.length + 1];
        body[0] = (byte) opcode.wire();
        System.arraycopy(payload, 0, body, 1, payload.length);
        return body;
    }
}
