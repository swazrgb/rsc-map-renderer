package openrsc.livemap.live;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.BankPreset;
import openrsc.bot.api.Duel;
import openrsc.bot.api.GameObject;
import openrsc.bot.api.GameState;
import openrsc.bot.api.GroundItem;
import openrsc.bot.api.Npc;
import openrsc.bot.api.Player;
import openrsc.bot.api.ProjectileEvent;
import openrsc.bot.api.TradeView;
import openrsc.bot.api.WallObject;
import openrsc.bot.api.WindowMove;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.MiningDef;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcDefs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.dto.MapEntityDto;
import openrsc.livemap.dto.PositionDto;
import openrsc.livemap.dto.ProjectileDto;
import openrsc.livemap.dto.WindowsDto;
import org.jspecify.annotations.Nullable;

/**
 * A bot's live record from what its session knows: its own state, and every NPC, player, object, wall object and
 * ground item in its view, named from the world's definitions. Landscape scenery and walls that match the world's
 * own are left out, as the map draws them itself; ones the view shows gone go out with {@code id} -1. A damage hit,
 * chat line or action bubble goes out for the few ticks after it happened. Stateless; it reads a {@link GameState} on
 * the caller's thread.
 */
public final class BotLiveEncoder {

    /** The fields of a bot's record its session does not know, which the application supplies. */
    public record Identity(
            String username,
            String status,
            @Nullable String scriptClass,
            @Nullable String task,
            @Nullable String description,
            @Nullable Long sessionId,
            @Nullable List<String> groups) {}

    private final ServerData data;

    public BotLiveEncoder(ServerData data) {
        this.data = data;
    }

    /** The record of a bot whose session is live. */
    public BotLiveDto encode(GameState bot, Identity who) {
        long tick = bot.sessionTick().tick();
        int idx = bot.selfServerIndex();
        return new BotLiveDto(
                who.username(),
                who.status(),
                who.scriptClass(),
                who.task(),
                who.description(),
                tick,
                position(bot),
                bot.fatigue().percent(),
                bot.fatigue().sleeping(),
                bot.inCombat(),
                idx >= 0 ? idx : null,
                npcs(bot),
                players(bot),
                objects(bot),
                walls(bot),
                who.sessionId(),
                bot.selfPanel().appearance().token(),
                bot.spriteDir(),
                bot.stats().level(Skill.HITS),
                bot.stats().baseLevel(Skill.HITS),
                bot.selfPanel().appearance().skulled() ? Boolean.TRUE : null,
                bot.tickEvents().tookDamage() ? bot.tickEvents().selfDamage() : null,
                groundItems(bot),
                freshChat(tick, bot.lastSelfChat(), bot.lastSelfChatTick()),
                freshChatTick(tick, bot.lastSelfChat(), bot.lastSelfChatTick()),
                projectiles(bot.tickEvents().projectiles()),
                freshBubble(tick, bot.selfActionBubbleItemId(), bot.selfActionBubbleTick()),
                freshBubbleTick(tick, bot.selfActionBubbleItemId(), bot.selfActionBubbleTick()),
                dialog(bot),
                combatLevel(bot),
                who.groups(),
                windows(bot));
    }

    /** The bot's tile; null while it is not known. */
    public static @Nullable PositionDto position(GameState bot) {
        if (!bot.pos().known()) {
            return null;
        }
        return new PositionDto(bot.pos().x(), bot.pos().y(), bot.pos().y() / 944);
    }

    /** The dialog options on screen; null when there are none. */
    public static @Nullable List<String> dialog(GameState bot) {
        List<String> opts = bot.dialog().options();
        return opts == null || opts.isEmpty() ? null : opts;
    }

    /** The bank, trade or duel screen on screen; null when there is none. */
    public static @Nullable WindowsDto windows(GameState bot) {
        WindowsDto.Trade trade = trade(bot);
        WindowsDto.Duel duel = duel(bot);
        ServerRules rules = bot.serverData().world().rules();
        WindowsDto.Bank bank = bot.bankOpen()
                ? new WindowsDto.Bank(
                        rules.bankNotes(),
                        !rules.equipmentTab(),
                        rules.customBanks(),
                        rules.customBanks() && rules.equipmentTab(),
                        rules.equipmentTab(),
                        rules.customBanks(),
                        WindowMove.presets(rules, 0) ? presets(bot) : null)
                : null;
        return bank != null || trade != null || duel != null ? new WindowsDto(bank, trade, duel) : null;
    }

    private static WindowsDto.@Nullable Trade trade(GameState bot) {
        TradeView t = bot.trade();
        if (t.confirmOpen()) {
            return new WindowsDto.Trade(
                    t.confirmPartner(), stacks(t.confirmMyOffer()), stacks(t.confirmOtherOffer()), true, true, true);
        }
        if (!t.open()) {
            return null;
        }
        return new WindowsDto.Trade(
                nameOf(bot, t.withServerIndex()),
                stacks(t.myOffer()),
                stacks(t.otherOffer()),
                t.shownAccepted(),
                t.shownOtherAccepted(),
                false);
    }

    private static WindowsDto.@Nullable Duel duel(GameState bot) {
        Duel d = bot.duel();
        if (!d.windowOpen() && !d.confirmOpen()) {
            return null;
        }
        String opponent = d.confirmOpen() && d.confirmOpponentName() != null
                ? d.confirmOpponentName()
                : nameOf(bot, d.opponentIndex());
        return new WindowsDto.Duel(
                opponent,
                staked(d.myStake()),
                staked(d.opponentStake()),
                d.myAccepted() != 0,
                d.otherAccepted() != 0,
                d.confirmOpen(),
                d.disallowRetreat() != 0,
                d.disallowMagic() != 0,
                d.disallowPrayer() != 0,
                d.disallowWeapons() != 0);
    }

    private static List<WindowsDto.Preset> presets(GameState bot) {
        Map<Integer, BankPreset> known = new HashMap<>();
        for (BankPreset p : bot.features().bankPresets()) {
            known.put(p.slot(), p);
        }
        List<WindowsDto.Preset> out = new ArrayList<>(WindowMove.PRESETS);
        for (int slot = 0; slot < WindowMove.PRESETS; slot++) {
            BankPreset p = known.get(slot);
            out.add(
                    p == null
                            ? new WindowsDto.Preset(slot, false, List.of(), List.of())
                            : new WindowsDto.Preset(slot, true, stacks(p.inventory()), stacks(p.equipment())));
        }
        return out;
    }

    private static List<WindowsDto.Stack> stacks(List<InventorySlot> slots) {
        return slots.stream()
                .map(slot -> new WindowsDto.Stack(slot.item().id(), slot.amount(), slot.noted()))
                .toList();
    }

    private static List<WindowsDto.Stack> staked(List<Duel.StakeItem> items) {
        return items.stream()
                .map(it -> new WindowsDto.Stack(it.catalogId(), it.amount(), it.noted()))
                .toList();
    }

    private static String nameOf(GameState bot, int serverIndex) {
        for (Player p : bot.players()) {
            if (p.serverIndex() == serverIndex) {
                return p.username();
            }
        }
        return "";
    }

    /** The combat level from the bot's base attack, defense, strength and hits, prayer and ranged left out. */
    public static int combatLevel(GameState bot) {
        return (bot.stats().baseLevel(Skill.ATTACK)
                        + bot.stats().baseLevel(Skill.DEFENSE)
                        + bot.stats().baseLevel(Skill.STRENGTH)
                        + bot.stats().baseLevel(Skill.HITS)
                        + 27)
                / 4;
    }

    /** Null for none. */
    public static @Nullable List<ProjectileDto> projectiles(List<ProjectileEvent> shots) {
        if (shots.isEmpty()) {
            return null;
        }
        List<ProjectileDto> out = new ArrayList<>(shots.size());
        for (ProjectileEvent s : shots) {
            out.add(new ProjectileDto(
                    s.spriteType(), s.casterIndex(), s.casterIsNpc(), s.victimIndex(), s.victimIsPlayer()));
        }
        return out;
    }

    public List<MapEntityDto> npcs(GameState bot) {
        NpcDefs defs = data.npcDefs();
        long tick = bot.sessionTick().tick();
        List<Npc> view = bot.npcs();
        List<MapEntityDto> out = new ArrayList<>(view.size());
        for (Npc n : view) {
            String name = defs == null ? null : defs.nameOf(n.id());
            out.add(MapEntityDto.npc(
                    n.serverIndex(),
                    n.id(),
                    name,
                    n.x(),
                    n.z(),
                    n.inCombat(),
                    n.sprite(),
                    npcHp(defs, n),
                    npcMaxHp(defs, n),
                    freshDamage(tick, n.lastDamage(), n.lastDamageTick()),
                    freshDamageTick(tick, n.lastDamageTick()),
                    freshChat(tick, n.lastChat(), n.lastChatTick()),
                    freshChatTick(tick, n.lastChat(), n.lastChatTick())));
        }
        return out;
    }

    private static @Nullable Integer npcHp(@Nullable NpcDefs defs, Npc n) {
        if (n.maxHits() > 0) {
            return n.currentHits();
        }
        NpcDef def = defs == null ? null : defs.get(n.id());
        return def == null || def.hits() <= 0 ? null : def.hits();
    }

    private static @Nullable Integer npcMaxHp(@Nullable NpcDefs defs, Npc n) {
        if (n.maxHits() > 0) {
            return n.maxHits();
        }
        NpcDef def = defs == null ? null : defs.get(n.id());
        return def == null || def.hits() <= 0 ? null : def.hits();
    }

    public List<MapEntityDto> players(GameState bot) {
        long tick = bot.sessionTick().tick();
        List<Player> view = bot.players();
        List<MapEntityDto> out = new ArrayList<>(view.size());
        for (Player p : view) {
            out.add(MapEntityDto.player(
                    p.serverIndex(),
                    p.username(),
                    p.x(),
                    p.z(),
                    p.inCombat(),
                    p.appearanceToken(),
                    p.sprite(),
                    p.maxHits() > 0 ? p.currentHits() : null,
                    p.maxHits() > 0 ? p.maxHits() : null,
                    freshDamage(tick, p.lastDamage(), p.lastDamageTick()),
                    freshDamageTick(tick, p.lastDamageTick()),
                    freshChat(tick, p.lastChat(), p.lastChatTick()),
                    freshChatTick(tick, p.lastChat(), p.lastChatTick()),
                    freshBubble(tick, p.actionBubbleItemId(), p.actionBubbleTick()),
                    freshBubbleTick(tick, p.actionBubbleItemId(), p.actionBubbleTick()),
                    p.combatLevel() > 0 ? p.combatLevel() : null,
                    p.skulled() ? Boolean.TRUE : null));
        }
        return out;
    }

    public List<MapEntityDto> groundItems(GameState bot) {
        return groundItems(bot.groundItems(), data.itemDefs());
    }

    /**
     * Every item of every pile, keyed by its tile and its rank there by id: two observers of one pile key it alike,
     * whatever order each received it in.
     */
    static List<MapEntityDto> groundItems(Iterable<GroundItem> items, @Nullable ItemDefs defs) {
        Map<Integer, List<GroundItem>> piles = new HashMap<>();
        for (GroundItem g : items) {
            piles.computeIfAbsent(groundKey(g.x(), g.z(), 0), k -> new ArrayList<>())
                    .add(g);
        }
        List<MapEntityDto> out = new ArrayList<>();
        for (List<GroundItem> pile : piles.values()) {
            pile.sort(Comparator.comparingInt(GroundItem::id));
            for (int rank = 0; rank < pile.size(); rank++) {
                GroundItem g = pile.get(rank);
                out.add(MapEntityDto.groundItem(
                        groundKey(g.x(), g.z(), rank),
                        g.id(),
                        defs == null ? null : defs.nameOf(g.id()),
                        g.x(),
                        g.z()));
            }
        }
        return out;
    }

    /** {@code x} below 1024, {@code z} below 4096 and {@code rank} below 1024. */
    static int groundKey(int x, int z, int rank) {
        return ((x & 0x3FF) << 22) | ((z & 0xFFF) << 10) | (rank & 0x3FF);
    }

    public List<MapEntityDto> objects(GameState bot) {
        List<GameObject> view = List.copyOf(bot.scenery().inView());
        List<MapEntityDto> out = new ArrayList<>(view.size());
        for (GameObject o : view) {
            if (isStaticScenery(o.x(), o.z(), o.dir(), o.id())) {
                continue;
            }
            out.add(new MapEntityDto(
                    objectKey(o.x(), o.z(), o.dir()),
                    o.id(),
                    objectName(o.id()),
                    o.x(),
                    o.z(),
                    false,
                    o.dir(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        appendObjectTombstones(out, view, bot);
        return out;
    }

    public List<MapEntityDto> walls(GameState bot) {
        List<WallObject> view = List.copyOf(bot.walls().inView());
        List<MapEntityDto> out = new ArrayList<>(view.size());
        for (WallObject w : view) {
            if (isStaticWall(w.x(), w.z(), w.dir(), w.id())) {
                continue;
            }
            String name = data.doorDefs() == null ? null : data.doorDefs().name(w.id());
            out.add(MapEntityDto.wall(objectKey(w.x(), w.z(), w.dir()), w.id(), name, w.x(), w.z(), w.dir()));
        }
        appendWallTombstones(out, view, !bot.scenery().inView().isEmpty(), bot);
        return out;
    }

    static int objectKey(int x, int z, int dir) {
        return ((x & 0x1FFF) << 16) | ((z & 0x1FFF) << 3) | (dir & 0x7);
    }

    private static long tileKey(int x, int z) {
        return ((long) x << 20) | (z & 0xfffff);
    }

    private @Nullable StaticLocIndex staticLocIndex() {
        return data.collisionMap().staticLocIndex();
    }

    private boolean isStaticScenery(int x, int z, int dir, int id) {
        StaticLocIndex index = staticLocIndex();
        if (index == null) {
            return false;
        }
        boolean[] match = {false};
        index.forEachSceneryAt(x, z, h -> {
            if (index.sceneryId(h) == id && (index.sceneryDir(h) & 7) == (dir & 7)) {
                match[0] = true;
            }
        });
        return match[0];
    }

    private boolean isStaticWall(int x, int z, int dir, int id) {
        StaticLocIndex index = staticLocIndex();
        if (index == null) {
            return false;
        }
        boolean[] match = {false};
        index.forEachBoundaryAt(x, z, dir, h -> {
            if (index.boundaryId(h) == id) {
                match[0] = true;
            }
        });
        return match[0];
    }

    private void appendObjectTombstones(List<MapEntityDto> out, Collection<GameObject> view, GameState bot) {
        StaticLocIndex index = staticLocIndex();
        if (view.isEmpty() || index == null) {
            return;
        }
        Set<Long> present = new HashSet<>(view.size() * 2);
        for (GameObject o : view) {
            present.add(tileKey(o.x(), o.z()));
        }
        int bx = bot.pos().x(), bz = bot.pos().y();
        int gr = data.world().liveViewGridRadius();
        for (int cy = (bz >> 3) - gr; cy <= (bz >> 3) + gr; cy++) {
            for (int cx = (bx >> 3) - gr; cx <= (bx >> 3) + gr; cx++) {
                index.forEachSceneryOriginInChunk(cx, cy, h -> {
                    int x = index.sceneryX(h), z = index.sceneryY(h);
                    if (present.contains(tileKey(x, z)) || !bot.inView(x, z)) {
                        return;
                    }
                    int dir = index.sceneryDir(h) & 7;
                    out.add(new MapEntityDto(
                            objectKey(x, z, dir),
                            -1,
                            null,
                            x,
                            z,
                            false,
                            dir,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
                });
            }
        }
    }

    private void appendWallTombstones(
            List<MapEntityDto> out, Collection<WallObject> view, boolean worldSynced, GameState bot) {
        StaticLocIndex index = staticLocIndex();
        if (!worldSynced || index == null) {
            return;
        }
        Set<Long> present = new HashSet<>(view.size() * 2);
        for (WallObject w : view) {
            present.add(((long) (w.dir() & 7) << 44) | tileKey(w.x(), w.z()));
        }
        int bx = bot.pos().x(), bz = bot.pos().y();
        int gr = data.world().liveViewGridRadius();
        for (int cy = (bz >> 3) - gr; cy <= (bz >> 3) + gr; cy++) {
            for (int cx = (bx >> 3) - gr; cx <= (bx >> 3) + gr; cx++) {
                index.forEachBoundaryInChunk(cx, cy, h -> {
                    int x = index.boundaryX(h), z = index.boundaryY(h);
                    int dir = index.boundaryDir(h) & 7;
                    if (present.contains(((long) dir << 44) | tileKey(x, z)) || !bot.inView(x, z)) {
                        return;
                    }
                    out.add(MapEntityDto.wall(objectKey(x, z, dir), -1, null, x, z, dir));
                });
            }
        }
    }

    /** A rock is named by its ore ("iron rock"), anything else by its definition. */
    private @Nullable String objectName(int id) {
        MiningDef m = data.mining(id);
        if (m != null) {
            ItemDef ore = data.item(m.oreId());
            if (ore != null && ore.name() != null) {
                String n = ore.name();
                String metal = n.toLowerCase(Locale.ROOT).endsWith(" ore") ? n.substring(0, n.length() - 4) : n;
                return metal + " rock";
            }
        }
        ObjectDef def = data.objectDefs() == null ? null : data.objectDefs().get(id);
        return def == null ? null : def.name();
    }

    /** A hit that landed in the last 2 ticks; null otherwise. */
    public static @Nullable Integer freshDamage(long tick, int dmg, long dmgTick) {
        return dmgTick >= 0 && tick - dmgTick <= 2 ? dmg : null;
    }

    public static @Nullable Long freshDamageTick(long tick, long dmgTick) {
        return dmgTick >= 0 && tick - dmgTick <= 2 ? dmgTick : null;
    }

    /** A chat line said in the last 5 ticks; null otherwise. */
    public static @Nullable String freshChat(long tick, @Nullable String msg, long msgTick) {
        return msg != null && msgTick >= 0 && tick - msgTick <= 5 ? msg : null;
    }

    public static @Nullable Long freshChatTick(long tick, @Nullable String msg, long msgTick) {
        return freshChat(tick, msg, msgTick) != null ? msgTick : null;
    }

    /** The item of an action bubble shown in the last 8 ticks; null otherwise. */
    public static @Nullable Integer freshBubble(long tick, int itemId, long bubbleTick) {
        return bubbleTick >= 0 && tick - bubbleTick <= 8 ? itemId : null;
    }

    public static @Nullable Long freshBubbleTick(long tick, int itemId, long bubbleTick) {
        return freshBubble(tick, itemId, bubbleTick) != null ? bubbleTick : null;
    }
}
