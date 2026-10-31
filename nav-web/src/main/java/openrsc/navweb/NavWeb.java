package openrsc.navweb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.zip.GZIPInputStream;
import openrsc.bot.api.ActionExecuteContext;
import openrsc.bot.api.Packet;
import openrsc.bot.api.TickKey;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.packets.AtObject2Packet;
import openrsc.bot.api.packets.AtObjectPacket;
import openrsc.bot.api.packets.AtWallObject2Packet;
import openrsc.bot.api.packets.AtWallObjectPacket;
import openrsc.bot.api.packets.AttackNpcPacket;
import openrsc.bot.api.packets.CastOnSceneryPacket;
import openrsc.bot.api.packets.CastOnSelfPacket;
import openrsc.bot.api.packets.ItemCommandPacket;
import openrsc.bot.api.packets.ItemEquipPacket;
import openrsc.bot.api.packets.ItemUnequipPacket;
import openrsc.bot.api.packets.NpcCommand1Packet;
import openrsc.bot.api.packets.NpcCommand2Packet;
import openrsc.bot.api.packets.TalkToNpcPacket;
import openrsc.bot.api.packets.UseItemOnBoundaryPacket;
import openrsc.bot.api.packets.UseItemOnObjectPacket;
import openrsc.bot.api.packets.WalkToPointPacket;
import openrsc.bot.api.store.BotKey;
import openrsc.gamedata.LoadProgress;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerDataSnapshot;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.cache.BoundedNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.planner.BobMapCampGenerator;
import openrsc.nav.planner.PathfinderAccount;
import openrsc.nav.planner.PathfinderInputs;
import openrsc.nav.planner.PathfinderPlans;
import openrsc.nav.planner.PathfinderReach;
import openrsc.nav.planner.PlannerJson;
import openrsc.nav.probe.WalkSession;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;
import openrsc.nav.walk.Yield;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The pathfinder's exports to JavaScript, compiled by TeaVM to WebAssembly. They take and answer JSON
 * strings, planner requests and responses in {@link PlannerJson}'s format; a failure answers
 * {@code {"error": message}} and prints its stack trace instead of throwing.
 *
 * <p>State is static: one world, pathfinder and walk per module instance. Call {@link #loadWorld},
 * then {@link #buildPathfinder}; an export that needs either answers an error before it.
 */
public final class NavWeb {

    private static final long MB = 1L << 20;

    /** Cache limits for a browser tab, a quarter to a sixth of {@link NavCacheBudget#DEFAULTS}. */
    private static final NavCacheBudget BUDGET = new NavCacheBudget(64 * MB, 16 * MB, 256 * MB);

    private static ServerData world;
    private static Pathfinder pathfinder;
    private static String inputs;
    private static WalkSession walk;

    private NavWeb() {}

    @SuppressWarnings("EmptyMethod") // TeaVM's main class needs one; nothing needs doing at load
    public static void main(String[] args) {}

    /**
     * Reads a gzipped {@link ServerDataSnapshot}, replacing the world and dropping the pathfinder and
     * walk built on the last one. Answers {@code world}, {@code unzipMs} and {@code readMs}. Each load
     * stage is posted to the page as it starts.
     */
    @JSExport
    public static String loadWorld(Int8Array gzippedSnapshot) {
        try (LoadProgress.Listening l = LoadProgress.listen(NavWeb::postProgress)) {
            LoadProgress.stage("unpacking the world");
            long t0 = System.currentTimeMillis();
            byte[] snapshot = gunzip(gzippedSnapshot.copyToJavaArray());
            long t1 = System.currentTimeMillis();
            world = ServerDataSnapshot.read(snapshot);
            long t2 = System.currentTimeMillis();
            pathfinder = null;
            inputs = null;
            walk = null;
            return "{\"world\":\"" + world.world().name() + "\",\"unzipMs\":" + (t1 - t0) + ",\"readMs\":" + (t2 - t1)
                    + "}";
        } catch (Throwable t) {
            return error(t);
        }
    }

    /** Builds the pathfinder over the loaded world, once. Answers {@code buildMs} and {@code transports}. */
    @JSExport
    public static String buildPathfinder() {
        try (LoadProgress.Listening l = LoadProgress.listen(NavWeb::postProgress)) {
            if (world == null) {
                throw new IllegalStateException("no world loaded");
            }
            long t0 = System.currentTimeMillis();
            if (pathfinder == null) {
                pathfinder = Pathfinder.load(world, new BoundedNavCaches(BUDGET));
            }
            return "{\"buildMs\":" + (System.currentTimeMillis() - t0) + ",\"transports\":"
                    + pathfinder.transports().size() + "}";
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JSExport
    public static String itemNames() {
        try {
            if (world == null) {
                throw new IllegalStateException("no world loaded");
            }
            return PlannerJson.itemNames(world.itemDefs(), world.world().rules().maxItemId());
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JSExport
    public static String wearableItems() {
        try {
            if (world == null) {
                throw new IllegalStateException("no world loaded");
            }
            return PlannerJson.wearableItems(world.itemDefs());
        } catch (Throwable t) {
            return error(t);
        }
    }

    /** The inventory slots a camp request's {@code items} take at their targets, as {@code {used, of}}. */
    @JSExport
    public static String campSlots(String json) {
        try {
            if (world == null) {
                throw new IllegalStateException("no world loaded");
            }
            int used = BobMapCampGenerator.slots(
                    world, PlannerJson.campRequest(json).items());
            return "{\"used\":" + used + ",\"of\":" + BobMapCampGenerator.INVENTORY_SLOTS + "}";
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JSExport
    public static String edibleItems() {
        try {
            if (world == null) {
                throw new IllegalStateException("no world loaded");
            }
            return PlannerJson.edibleItems(world.edibleHeals());
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JSExport
    public static String inputs() {
        try {
            if (inputs == null) {
                inputs = PlannerJson.write(PathfinderInputs.of(requireLoaded()));
            }
            return inputs;
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JSExport
    public static String plan(String json) {
        try {
            return PlannerJson.write(PathfinderPlans.plan(requireLoaded(), null, PlannerJson.request(json)));
        } catch (Throwable t) {
            return error(t);
        }
    }

    @JSExport
    public static String preview(String json) {
        try {
            return PlannerJson.write(PathfinderPlans.preview(requireLoaded(), null, PlannerJson.request(json)));
        } catch (Throwable t) {
            return error(t);
        }
    }

    /**
     * What a page needs to stand in for the server: the map's {@code width} and {@code height} in
     * tiles, every NPC spawn as {@code [id, x, z, min x, min z, max x, max z]}, and for each spawned id
     * its {@code level} and whether it is {@code aggressive}, which the server takes as aggressive and
     * attackable.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/NPCDef.java#L191">NPCDef.isAggressive</a>
     */
    @JSExport
    public static String serverWorld() {
        try {
            if (world == null) {
                throw new IllegalStateException("no world loaded");
            }
            StringBuilder b = new StringBuilder("{\"width\":")
                    .append(CollisionMap.WIDTH)
                    .append(",\"height\":")
                    .append(CollisionMap.HEIGHT)
                    .append(",\"spawns\":[");
            Map<Integer, NpcDef> defs = new TreeMap<>();
            boolean firstSpawn = true;
            for (NpcLocs.Spawn sp : world.npcLocs()) {
                if (!firstSpawn) {
                    b.append(',');
                }
                firstSpawn = false;
                b.append('[')
                        .append(sp.id())
                        .append(',')
                        .append(sp.start().x())
                        .append(',')
                        .append(sp.start().z())
                        .append(',')
                        .append(sp.min().x())
                        .append(',')
                        .append(sp.min().z())
                        .append(',')
                        .append(sp.max().x())
                        .append(',')
                        .append(sp.max().z())
                        .append(']');
                NpcDef d = world.npc(sp.id());
                if (d != null) {
                    defs.put(sp.id(), d);
                }
            }
            b.append("],\"defs\":{");
            boolean firstDef = true;
            for (NpcDef d : defs.values()) {
                if (!firstDef) {
                    b.append(',');
                }
                firstDef = false;
                b.append('"')
                        .append(d.id())
                        .append("\":{\"level\":")
                        .append(d.combatLevel())
                        .append(",\"aggressive\":")
                        .append(d.aggressive() && d.attackable())
                        .append('}');
            }
            return b.append("}}").toString();
        } catch (Throwable t) {
            return error(t);
        }
    }

    /**
     * Every declarative transport but the bespoke ones, as a page standing in for the server needs it:
     * its {@code id}; its target {@code k} ({@code obj} or {@code wall} at {@code x, z} and a wall's
     * {@code dir}, {@code npc} with {@code npcs}, or {@code self}); its action {@code act}
     * ({@code click} or {@code npcCommand} with {@code cmd}, {@code use} or {@code cmd} with
     * {@code item}, -1 when it is chosen from the pack, or {@code cast} with {@code spell}); and its
     * landing, as {@code e}, flat {@code [entry x, z, landing x, z]} runs, or {@code free}, one landing
     * from anywhere.
     */
    @JSExport
    public static String serverTransports() {
        try {
            StringBuilder b = new StringBuilder("[");
            boolean firstOne = true;
            for (Transport t : requireLoaded().transports()) {
                if (!(t instanceof DeclarativeTransport d)
                        || d.engageSpec().action() instanceof EngageSpec.Action.Bespoke) {
                    continue;
                }
                EngageSpec spec = d.engageSpec();
                StringBuilder o = new StringBuilder("{\"id\":");
                string(o, t.name());
                o.append(",\"k\":");
                switch (spec.target()) {
                    case EngageSpec.Target.Scenery sc ->
                        o.append("\"obj\",\"x\":")
                                .append(sc.x())
                                .append(",\"z\":")
                                .append(sc.y());
                    case EngageSpec.Target.Boundary w ->
                        o.append("\"wall\",\"x\":")
                                .append(w.x())
                                .append(",\"z\":")
                                .append(w.y())
                                .append(",\"dir\":")
                                .append(w.dir());
                    case EngageSpec.Target.Npcs n -> {
                        o.append("\"npc\",\"npcs\":[");
                        for (int i = 0; i < n.serverIndexes().length; i++) {
                            o.append(i > 0 ? "," : "").append(n.serverIndexes()[i]);
                        }
                        o.append(']');
                    }
                    case EngageSpec.Target.Self self -> o.append("\"self\"");
                }
                o.append(",\"act\":");
                switch (spec.action()) {
                    case EngageSpec.Action.Interact i ->
                        o.append("\"click\",\"cmd\":").append(i.command());
                    case EngageSpec.Action.NpcCommand n ->
                        o.append("\"npcCommand\",\"cmd\":").append(n.command());
                    case EngageSpec.Action.UseItem u ->
                        o.append("\"use\",\"item\":").append(itemOf(u.selector()));
                    case EngageSpec.Action.ItemCommand c ->
                        o.append("\"cmd\",\"item\":").append(itemOf(c.selector()));
                    case EngageSpec.Action.Cast c ->
                        o.append("\"cast\",\"spell\":").append(c.spellId());
                    case EngageSpec.Action.Bespoke x -> {
                        continue;
                    }
                }
                if (t.entryFree()) {
                    Point to = t.destinationFrom(Point.of(0, 0));
                    o.append(",\"free\":[")
                            .append(to.x())
                            .append(',')
                            .append(to.y())
                            .append(']');
                } else {
                    o.append(",\"e\":[");
                    boolean firstEntry = true;
                    for (Point e : t.entries()) {
                        Point to = t.destinationFrom(e);
                        if (to == null) {
                            continue;
                        }
                        o.append(firstEntry ? "" : ",")
                                .append(e.x())
                                .append(',')
                                .append(e.y())
                                .append(',')
                                .append(to.x())
                                .append(',')
                                .append(to.y());
                        firstEntry = false;
                    }
                    o.append(']');
                }
                b.append(firstOne ? "" : ",").append(o).append('}');
                firstOne = false;
            }
            return b.append(']').toString();
        } catch (Throwable t) {
            return error(t);
        }
    }

    private static int itemOf(EngageSpec.ItemSelector selector) {
        return selector instanceof EngageSpec.ItemSelector.Fixed f ? f.itemId() : -1;
    }

    /**
     * The world's static walk graph, one byte per tile in {@link CollisionMap#staticSteps}'s layout;
     * {@code null} before a world is loaded.
     */
    @JSExport
    public static Int8Array stepMap() {
        return world == null
                ? null
                : Int8Array.copyFromJavaArray(world.collisionMap().staticSteps());
    }

    /**
     * Starts walking the request {@code json}, replacing any walk before it; the page then calls
     * {@link #walkTick} once a tick. Answers the {@code viewRadius} in tiles NPCs are seen within and
     * the account's {@code combatLevel}.
     */
    @JSExport
    public static String walkStart(String json) {
        try {
            walk = null;
            walk = PathfinderPlans.session(requireLoaded(), null, PlannerJson.request(json));
            return "{\"viewRadius\":" + walk.viewRadius() + ",\"combatLevel\":" + walk.combatLevel() + "}";
        } catch (Throwable t) {
            return error(t);
        }
    }

    /**
     * One tick of the walk, standing on {@code (x, z)}, fighting or not, and seeing {@code npcs}, five
     * ints each: server index, id, x, z, and 1 while it fights. Answers the {@code move}, with {@code why} when stuck and
     * {@code hop} on a hop; {@code unsent}, why a move has no packets; the {@code packets} sent, in
     * order (see {@code packet}); the {@code path} ahead; the {@code pack} as {@code [id, count, worn]},
     * the {@code bank} as {@code [id, count]}, and what is {@code worn} as
     * {@code [wear slot, appearance id]}, the pair the server keeps per worn slot.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L3026">Player.updateWornItems</a>
     */
    @JSExport
    public static String walkTick(int x, int z, boolean inCombat, Int32Array npcs) {
        try {
            if (walk == null) {
                throw new IllegalStateException("no walk started");
            }
            ServerData env = requireLoaded().serverData();
            int[] raw = npcs.copyToJavaArray();
            List<Seen> seen = new ArrayList<>(raw.length / 5);
            for (int i = 0; i + 4 < raw.length; i += 5) {
                NpcDef d = env.npc(raw[i + 1]);
                if (d != null) {
                    seen.add(new Seen(raw[i], raw[i + 1], raw[i + 2], raw[i + 3], raw[i + 4] != 0, d));
                }
            }
            WalkSession.Tick tick = walk.tick(new WalkSession.View(Point.of(x, z), inCombat, seen));
            StringBuilder b = new StringBuilder("{\"move\":");
            string(
                    b,
                    switch (tick.move()) {
                        case WalkSession.Walk w -> "walk";
                        case WalkSession.Hold h -> "hold";
                        case WalkSession.BackOff o -> "backOff";
                        case WalkSession.Wait w -> "wait";
                        case WalkSession.Attack k -> "attack";
                        case WalkSession.Wear w -> w.on() ? "equip" : "unequip";
                        case WalkSession.Hop h -> "hop";
                        case WalkSession.Bank k -> "bank";
                        case WalkSession.Arrived a -> "arrived";
                        case WalkSession.Stuck s -> "stuck";
                    });
            if (tick.move() instanceof WalkSession.Stuck s) {
                b.append(",\"why\":");
                string(b, s.why());
            }
            if (tick.move() instanceof WalkSession.Hop h) {
                b.append(",\"hop\":");
                string(b, h.step().hopName());
            }
            if (tick.unsent() != null) {
                b.append(",\"unsent\":");
                string(b, tick.unsent());
            }
            b.append(",\"packets\":[");
            if (tick.send() != null) {
                List<Packet> sent = new ArrayList<>();
                tick.send().execute(new Collect(sent));
                for (int i = 0; i < sent.size(); i++) {
                    if (i > 0) {
                        b.append(',');
                    }
                    packet(b, sent.get(i), tick.held());
                }
            }
            b.append(']');
            List<Point> path = walk.path();
            if (path != null) {
                b.append(",\"path\":");
                points(b, path);
            }
            Map<Integer, Long> carried = new LinkedHashMap<>();
            Map<Integer, Boolean> wearing = new LinkedHashMap<>();
            for (InventorySlot slot : walk.pack().slots()) {
                carried.merge(slot.id(), (long) slot.amount(), Long::sum);
                wearing.merge(slot.id(), walk.wearing().contains(slot.id()), (a, c) -> a || c);
            }
            b.append(",\"pack\":[");
            boolean firstItem = true;
            for (Map.Entry<Integer, Long> e : carried.entrySet()) {
                b.append(firstItem ? "" : ",")
                        .append('[')
                        .append(e.getKey())
                        .append(',')
                        .append(e.getValue())
                        .append(',')
                        .append(Boolean.TRUE.equals(wearing.get(e.getKey())) ? 1 : 0)
                        .append(']');
                firstItem = false;
            }
            b.append("],\"bank\":");
            counts(b, tally(walk.bank().slots(), s -> s.item().id(), s -> (long) s.amount()));
            b.append(",\"worn\":[");
            boolean firstWorn = true;
            for (Map.Entry<Integer, Boolean> e : wearing.entrySet()) {
                ItemDef def = Boolean.TRUE.equals(e.getValue()) ? env.item(e.getKey()) : null;
                if (def == null || !def.wearable()) {
                    continue;
                }
                b.append(firstWorn ? "" : ",")
                        .append('[')
                        .append(def.wearSlot())
                        .append(',')
                        .append(def.appearanceId())
                        .append(']');
                firstWorn = false;
            }
            b.append(']');
            return b.append('}').toString();
        } catch (Throwable t) {
            return error(t);
        }
    }

    /**
     * One packet as {@code {"t": kind, ...}} with its fields. A slot is written as the id of the item
     * in it in {@code held}, or -1 when the slot is empty; a packet with no case here is written as its
     * class's simple name.
     */
    private static void packet(StringBuilder b, Packet p, Inventory held) {
        b.append("{\"t\":");
        switch (p) {
            case WalkToPointPacket w -> {
                string(b, "walk");
                b.append(",\"tiles\":");
                points(b, w.waypoints());
            }
            case AttackNpcPacket a -> {
                string(b, "attack");
                b.append(",\"npc\":").append(a.npcServerIndex());
            }
            case AtObjectPacket o -> object(b, "atObject", o.x(), o.y()).append(",\"cmd\":1");
            case AtObject2Packet o -> object(b, "atObject", o.x(), o.y()).append(",\"cmd\":2");
            case AtWallObjectPacket w ->
                wall(b, "atWall", w.x(), w.y(), w.direction()).append(",\"cmd\":1");
            case AtWallObject2Packet w ->
                wall(b, "atWall", w.x(), w.y(), w.direction()).append(",\"cmd\":2");
            case UseItemOnObjectPacket u ->
                object(b, "useOnObject", u.x(), u.y()).append(",\"item\":").append(itemIn(held, u.slot()));
            case UseItemOnBoundaryPacket u ->
                wall(b, "useOnWall", u.x(), u.y(), u.direction())
                        .append(",\"item\":")
                        .append(itemIn(held, u.slot()));
            case CastOnSceneryPacket c ->
                object(b, "castOnObject", c.x(), c.y()).append(",\"spell\":").append(c.spellId());
            case CastOnSelfPacket c -> {
                string(b, "castSelf");
                b.append(",\"spell\":").append(c.spellId());
            }
            case ItemCommandPacket c -> {
                string(b, "itemCommand");
                b.append(",\"item\":").append(itemIn(held, c.slot()));
            }
            case ItemEquipPacket e -> {
                string(b, "equip");
                b.append(",\"item\":").append(itemIn(held, e.slot()));
            }
            case ItemUnequipPacket u -> {
                string(b, "unequip");
                b.append(",\"item\":").append(itemIn(held, u.slot()));
            }
            case NpcCommand1Packet n -> {
                string(b, "npcCommand");
                b.append(",\"npc\":").append(n.npcServerIndex()).append(",\"cmd\":1");
            }
            case NpcCommand2Packet n -> {
                string(b, "npcCommand");
                b.append(",\"npc\":").append(n.npcServerIndex()).append(",\"cmd\":2");
            }
            case TalkToNpcPacket t -> {
                string(b, "talk");
                b.append(",\"npc\":").append(t.npcServerIndex());
            }
            default -> string(b, p.getClass().getSimpleName());
        }
        b.append('}');
    }

    private static StringBuilder object(StringBuilder b, String kind, int x, int z) {
        string(b, kind);
        return b.append(",\"x\":").append(x).append(",\"z\":").append(z);
    }

    private static StringBuilder wall(StringBuilder b, String kind, int x, int z, int dir) {
        return object(b, kind, x, z).append(",\"dir\":").append(dir);
    }

    private static int itemIn(Inventory held, int slot) {
        for (InventorySlot s : held.slots()) {
            if (s.slot() == slot) {
                return s.id();
            }
        }
        return -1;
    }

    private record Collect(List<Packet> sent) implements ActionExecuteContext {
        @Override
        public void describe(String message) {}

        @Override
        public void writePacket(Packet packet) {
            sent.add(packet);
        }

        @Override
        public <T> void store(BotKey<T> key, T value) {}

        @Override
        public <T> void storeDelete(BotKey<T> key) {}

        @Override
        public <T> void note(TickKey<T> key, T value) {}
    }

    private record Seen(int serverIndex, int id, int x, int z, boolean inCombat, NpcDef def)
            implements Yield.Sighting {}

    private static void counts(StringBuilder b, Map<Integer, Long> byItem) {
        b.append('[');
        boolean first = true;
        for (Map.Entry<Integer, Long> e : byItem.entrySet()) {
            b.append(first ? "" : ",")
                    .append('[')
                    .append(e.getKey())
                    .append(',')
                    .append(e.getValue())
                    .append(']');
            first = false;
        }
        b.append(']');
    }

    private static <T> Map<Integer, Long> tally(List<T> slots, ToIntFunction<T> id, ToLongFunction<T> amount) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (T slot : slots) {
            out.merge(id.applyAsInt(slot), amount.applyAsLong(slot), Long::sum);
        }
        return out;
    }

    private static void points(StringBuilder b, List<Point> tiles) {
        b.append('[');
        for (int i = 0; i < tiles.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(tiles.get(i).x()).append(',').append(tiles.get(i).y());
        }
        b.append(']');
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    b.append('\\').append(c);
                } else if (c >= 0x20) {
                    b.append(c);
                }
            }
        }
        b.append('"');
    }

    /** {@link BobMapCampGenerator#compile} for the request {@code json}, in {@link PlannerJson#campRequest}'s format. */
    @JSExport
    public static String campRecipe(String json) {
        try {
            return PlannerJson.write(BobMapCampGenerator.compile(requireLoaded(), PlannerJson.campRequest(json)));
        } catch (Throwable t) {
            return error(t);
        }
    }

    /** {@link PathfinderReach#of} for the request {@code json}. */
    @JSExport
    public static String reachable(String json) {
        try {
            return PlannerJson.write(PathfinderReach.of(requireLoaded(), null, PlannerJson.request(json)));
        } catch (Throwable t) {
            return error(t);
        }
    }

    /** {@link PathfinderAccount#of} for the request {@code json}. */
    @JSExport
    public static String account(String json) {
        try {
            return PlannerJson.write(PathfinderAccount.of(requireLoaded(), null, PlannerJson.request(json)));
        } catch (Throwable t) {
            return error(t);
        }
    }

    /** Posts {@code {progress: stage, at: ms}} to the worker's own message channel. */
    @JSBody(params = "stage", script = "self.postMessage({progress: stage, at: performance.now()});")
    private static native void postProgress(String stage);

    private static Pathfinder requireLoaded() {
        if (pathfinder == null) {
            throw new IllegalStateException("no pathfinder built");
        }
        return pathfinder;
    }

    private static byte[] gunzip(byte[] packed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(packed.length * 40);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static String error(Throwable t) {
        t.printStackTrace();
        String msg = String.valueOf(t.getMessage() == null ? t.getClass().getName() : t.getMessage());
        StringBuilder b = new StringBuilder("{\"error\":\"");
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.append("\"}").toString();
    }
}
