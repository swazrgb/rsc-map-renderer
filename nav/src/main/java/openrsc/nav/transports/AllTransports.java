package openrsc.nav.transports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transport registry: every registry enum's constants, and the transports they expand to in a
 * world.
 *
 * <p>Each transport is declared by exactly one {@link TransportDef} constant, and its name derives
 * from that constant ({@link #named}, {@link #namedByPlacement}). A name is what a cost override or a
 * prohibition addresses and what a route hop carries.
 */
public final class AllTransports {

    private static final Logger LOG = LoggerFactory.getLogger(AllTransports.class);

    /**
     * Every constant of every registry. Built without a world, at class initialisation, which throws
     * when two registries declare the same name. The registries are listed by hand, not found by
     * reflection, so that the TeaVM build of nav keeps them.
     */
    public static final List<TransportDef> ALL_DEFS;

    static {
        List<TransportDef> defs = new ArrayList<>();
        defs.addAll(List.of(Webs.values()));
        defs.addAll(List.of(Vines.values()));
        defs.addAll(List.of(Levers.values()));
        defs.addAll(List.of(SpiritTreeRoutes.values()));
        defs.addAll(List.of(NpcRoutes.values()));
        defs.addAll(List.of(FlameWalls.values()));
        defs.addAll(List.of(JungleTrees.values()));
        defs.addAll(List.of(QuestObstacles.values()));
        defs.addAll(List.of(SpecialCrossings.values()));
        defs.addAll(List.of(TeleportSpellRoutes.values()));
        defs.addAll(List.of(LadderSpecials.values()));
        defs.addAll(List.of(Climbs.values()));
        defs.addAll(List.of(AgilityObstacles.values()));
        defs.addAll(List.of(SceneryCrossings.values()));
        defs.addAll(List.of(WallCrossings.values()));
        defs.addAll(List.of(MenuDoors.values()));
        defs.addAll(List.of(ItemTeleports.values()));
        defs.addAll(List.of(SpecialDoor.values()));
        defs.addAll(List.of(ItemUses.values()));
        defs.addAll(List.of(Portals.values()));
        Set<String> seen = new HashSet<>(defs.size() * 2);
        List<String> duplicates = new ArrayList<>();
        for (TransportDef d : defs) {
            if (!seen.add(d.name())) {
                duplicates.add(d.name());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("duplicate transport ids across registries: " + duplicates);
        }
        ALL_DEFS = List.copyOf(defs);
    }

    private static final Set<String> DECLARED_KEYS;

    static {
        Set<String> keys = new HashSet<>();
        for (TransportDef d : ALL_DEFS) {
            keys.add(d.name());
        }
        for (SpecialDoor d : SpecialDoor.values()) {
            keys.add(SpecialDoor.armKey(d.name(), true));
            keys.add(SpecialDoor.armKey(d.name(), false));
        }
        DECLARED_KEYS = Set.copyOf(keys);
    }

    /**
     * Whether {@code key} names a transport in some world: a constant, a {@link SpecialDoor} arm, or,
     * for a constant derived from every placement, one placement ({@code C@x_y}), one arm of it
     * ({@code C@x_y#lowToHigh}, {@code #highToLow} or an entry {@code #x_y}) or one direction at every
     * placement ({@code C#lowToHigh}). Which placements exist is not checked, since that is per world.
     */
    public static boolean declares(String key) {
        if (DECLARED_KEYS.contains(key)) {
            return true;
        }
        Matcher m = DERIVED_KEY.matcher(key);
        return m.matches() && DERIVED.contains(m.group(1));
    }

    private static final Pattern DERIVED_KEY = Pattern.compile(
            "([A-Z0-9_]+)(?:@\\d+_\\d+(?:#(?:lowToHigh|highToLow|\\d+_\\d+))?|#(?:lowToHigh|highToLow))");

    private static final Set<String> DERIVED;

    static {
        Set<String> derived = new HashSet<>();
        for (TransportDef d : ALL_DEFS) {
            if (d.spec().namedByPlacement()) {
                derived.add(d.name());
            }
        }
        DERIVED = Set.copyOf(derived);
    }

    private AllTransports() {}

    /**
     * Every transport in {@code env}, built once per world and shared: the same list instance for the
     * same {@code env}, which is what lets the engine reuse a cluster graph built over it. Beyond
     * expanding the constants, it pins each NPC-talk transport to the spawns of its NPCs and anchors
     * each object-interact transport not pinned by its row on the nearest placement of its object
     * within 8 tiles of its entry. Thread-safe.
     */
    public static List<Transport> defaultRegistry(ServerData env) {
        return REGISTRIES.computeIfAbsent(env, AllTransports::buildRegistry);
    }

    private static final Map<ServerData, List<Transport>> REGISTRIES = Collections.synchronizedMap(new WeakHashMap<>());

    private static List<Transport> buildRegistry(ServerData env) {
        List<Transport> transports = declared(env);
        int pinned = 0;
        for (Transport t : transports) {
            if (t instanceof NpcTalkTransport talk) {
                talk.deriveSpawns(env, 15);
                pinned++;
            }
        }
        LOG.info("AllTransports: derived spawn indexes for {} npc-talk transports", pinned);
        int anchored = 0;
        for (Transport t : transports) {
            if (t instanceof ObjectInteractTransport oit && !oit.anchorPinned()) {
                int bestX = 0;
                int bestY = 0;
                int bestD = Integer.MAX_VALUE;
                for (SceneryLocs.Loc loc : env.sceneryLocs()) {
                    if (loc.id() != oit.objectId()) {
                        continue;
                    }
                    int d = Math.max(
                            Math.abs(loc.pos().x() - oit.entryX()),
                            Math.abs(loc.pos().y() - oit.entryY()));
                    if (d < bestD) {
                        bestD = d;
                        bestX = loc.pos().x();
                        bestY = loc.pos().y();
                    }
                }
                if (bestD <= 8) {
                    oit.atScenery(bestX, bestY);
                    anchored++;
                } else {
                    LOG.warn(
                            "AllTransports: no id-{} placement within 8 tiles of entry ({},{})"
                                    + " for '{}' — anchor left at the entry tile (data suspect)",
                            oit.objectId(),
                            oit.entryX(),
                            oit.entryY(),
                            t.name());
                }
            }
        }
        LOG.info("AllTransports: derived scenery anchors for {} object-interact transports", anchored);
        LOG.info("AllTransports: built {} transports from {} declared constants", transports.size(), ALL_DEFS.size());
        return List.copyOf(transports);
    }

    private static List<Transport> declared(ServerData env) {
        List<Transport> out = new ArrayList<>(2300);
        out.addAll(SpecialCrossings.all(env));
        out.addAll(LadderSpecials.all(env));
        out.addAll(Climbs.all(env));
        out.addAll(Levers.all(env));
        out.addAll(QuestObstacles.all(env));
        out.addAll(AgilityObstacles.all(env));
        out.addAll(TeleportSpellRoutes.all(env));
        out.addAll(SpecialDoor.all(env));
        out.addAll(NpcRoutes.all(env));
        out.addAll(SceneryCrossings.all(env));
        out.addAll(WallCrossings.all(env));
        out.addAll(MenuDoors.all(env));
        out.addAll(ItemTeleports.all(env));
        out.addAll(SpiritTreeRoutes.all(env));
        out.addAll(Webs.all(env));
        out.addAll(Vines.all(env));
        out.addAll(JungleTrees.all(env));
        out.addAll(FlameWalls.all(env));
        out.addAll(ItemUses.all(env));
        out.addAll(Portals.all(env));
        // The order is each transport's index in the engine, which the nav cache digests: reordering
        // the registries invalidates every cached plane.
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof WallObjectTransport w) {
                out.set(i, w.withEdgeFrom(env.boundaryLocs()));
            }
        }
        requireUniqueNames(out);
        return out;
    }

    /**
     * Names the transports of one constant: the constant itself when there is one, else
     * {@code CONSTANT#arm}. The arm is the transport's own {@link Transport#arm} when it has one, else
     * {@code lowToHigh} or {@code highToLow} for a pair crossing one edge both ways (by x + y, then by
     * x), else its first entry tile {@code x_y}. Returns {@code arms}.
     */
    public static List<Transport> named(String constant, List<Transport> arms) {
        boolean pair = isReversePair(arms);
        for (Transport t : arms) {
            String arm = t.arm();
            if (arm == null && arms.size() > 1) {
                Point entry = t.entries().get(0);
                if (pair) {
                    Point dest = t.destinationFrom(entry);
                    int rise = dest.x() + dest.y() - (entry.x() + entry.y());
                    arm = (rise != 0 ? rise > 0 : dest.x() > entry.x()) ? "lowToHigh" : "highToLow";
                } else {
                    arm = entry.x() + "_" + entry.y();
                }
            }
            t.assignName(arm == null ? constant : constant + "#" + arm);
        }
        return arms;
    }

    /**
     * Names the transports of a constant derived from every placement of an object by the placement
     * each engages, {@code CONSTANT@x_y} (the wall's tile or the scenery's anchor), then within one
     * placement as {@link #named} does. The same object on the same tile has the same name in every
     * world. Throws when a transport engages no placement. Returns {@code arms}.
     */
    public static List<Transport> namedByPlacement(String constant, List<Transport> arms) {
        Map<Point, List<Transport>> byPlacement = new LinkedHashMap<>();
        for (Transport t : arms) {
            byPlacement.computeIfAbsent(placementOf(t), p -> new ArrayList<>()).add(t);
        }
        for (Map.Entry<Point, List<Transport>> e : byPlacement.entrySet()) {
            named(placementKey(constant, e.getKey()), e.getValue());
        }
        return arms;
    }

    public static String placementKey(String constant, Point tile) {
        return constant + "@" + tile.x() + "_" + tile.y();
    }

    private static Point placementOf(Transport t) {
        EngageSpec.Target.Boundary wall = t.boundary();
        if (wall != null) {
            return Point.of(wall.x(), wall.y());
        }
        Point anchor = t.sceneryAnchor();
        if (anchor == null) {
            throw new IllegalStateException(t.name() + " engages no placement to be named by");
        }
        return anchor;
    }

    private static void requireUniqueNames(List<Transport> transports) {
        Set<String> names = new HashSet<>();
        for (Transport t : transports) {
            if (!names.add(t.name())) {
                throw new IllegalStateException("two transports named " + t.name());
            }
        }
    }

    private static boolean isReversePair(List<Transport> arms) {
        if (arms.size() != 2
                || arms.get(0).entries().size() != 1
                || arms.get(1).entries().size() != 1) {
            return false;
        }
        Point a = arms.get(0).entries().get(0);
        Point b = arms.get(1).entries().get(0);
        return a.equals(arms.get(1).destinationFrom(b)) && b.equals(arms.get(0).destinationFrom(a));
    }
}
