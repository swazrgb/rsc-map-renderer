package openrsc.nav;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.TeleportRegion;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.eligibility.EligibilityInputs;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.EngageSpec;

/**
 * A way across the map that is not a walk: a door, gate, ladder, shortcut, ship, teleport. The
 * planner treats it as a directed edge from any entry tile to {@link #destinationFrom}, costing
 * {@link #cost()}, open to an account only when {@link #prereq()} passes for it.
 *
 * <p>A transport is data: it reads no live state and does not act. Everything the planner reads of
 * it is fixed once the registry is assembled, so one instance serves every account and thread.
 */
public interface Transport {

    /** {@code count} of {@code item}, taken by one use. */
    record Consumed(ItemId item, long count) {}

    /** Whether it may be taken from {@code (x, y)}: one of its entries, or a tile of its region. */
    boolean isEntry(int x, int y);

    /** The tiles it may be taken from. Empty only for an {@link #entryFree()} transport. */
    List<Point> entries();

    /**
     * Where it may be taken from when that is a region rather than listed entries, as for a teleport;
     * null for a transport taken from its {@link #entries()}.
     */
    default @Nullable TeleportRegion region() {
        return null;
    }

    /** Whether it is taken anywhere in its {@link #region()} rather than from an entry tile. */
    default boolean entryFree() {
        return region() != null;
    }

    /** The tile taking it from {@code from} lands on. */
    Point destinationFrom(Point from);

    /**
     * Whether {@link #destinationFrom} differs between entries, as for a ladder that keeps the x it
     * is climbed from. When it does, only a route hop's own entry lands on that hop's landing.
     */
    default boolean landingDependsOnEntry() {
        return false;
    }

    /**
     * The wall object it goes through, by its id, tile and edge; null when it is not one. Describes
     * the transport for maps and tools; the planner does not read it.
     */
    default EngageSpec.Target.Boundary boundary() {
        return null;
    }

    /**
     * The anchor tile of the scenery object it uses; null when it uses none. Like {@link #boundary()},
     * a description the planner does not read.
     */
    default Point sceneryAnchor() {
        return null;
    }

    /** The item ids its gate needs worn ({@code WEARING} facts), in declaration order. */
    default Set<Integer> wornGate() {
        Set<Integer> out = new LinkedHashSet<>();
        for (EligibilityInputs.Fact f : eligibilityInputs().facts()) {
            if (f.kind() == EligibilityInputs.Fact.Kind.WEARING) {
                out.add(f.itemId());
            }
        }
        return out;
    }

    /**
     * {@link #wornGate()} with alternatives narrowed to one: of gate items that cannot be worn
     * together, the one {@code pack} wears, else one it carries, else the first declared.
     */
    default Set<Integer> wornGate(Inventory pack, ServerData serverData) {
        List<Integer> gate = List.copyOf(wornGate());
        Set<Integer> out = new LinkedHashSet<>();
        for (int id : gate) {
            int pick = id;
            for (int other : gate) {
                if (other != id && (serverData.displaces(id, other) || serverData.displaces(other, id))) {
                    pick = preferredOf(pick, other, pack, gate);
                }
            }
            out.add(pick);
        }
        return out;
    }

    private static int preferredOf(int a, int b, Inventory pack, List<Integer> gate) {
        if (pack.isEquipped(a) != pack.isEquipped(b)) {
            return pack.isEquipped(a) ? a : b;
        }
        if ((pack.count(a) > 0) != (pack.count(b) > 0)) {
            return pack.count(a) > 0 ? a : b;
        }
        return gate.indexOf(a) <= gate.indexOf(b) ? a : b;
    }

    /**
     * The highest current Hits level the gate asks for, or 0 for none. The planner passes such a gate
     * on base Hits while food is carried, so this is the level to eat up to before taking it.
     */
    default int hitsGate() {
        int need = 0;
        for (EligibilityInputs.Fact f : eligibilityInputs().facts()) {
            if (f.foodLifts()) {
                need = Math.max(need, f.threshold());
            }
        }
        return need;
    }

    /** Who may take it. {@link Prereq#ALWAYS}, the default, opens it to every account. */
    default Prereq prereq() {
        return Prereq.ALWAYS;
    }

    /**
     * The facts {@link #prereq()} reads, which the planner keys its per-account caches on. Derived
     * from the prereq; a transport that overrides it must name every fact its gate reads.
     */
    default EligibilityInputs eligibilityInputs() {
        return prereq().inputs();
    }

    /**
     * What one use takes from the pack: fares, tolls, runes, a charge. Judged against
     * {@code account} as it stands on reaching this transport, after earlier hops' fares, and read
     * like {@link #prereq()}: only the declared facts.
     */
    default List<Consumed> consumes(AccountFacts account) {
        return List.of();
    }

    /**
     * What taking it costs a route, in deci-ticks (10 to a tick, the cost of one walked tile): about
     * the ticks it takes, approach, dialogue and server delay included.
     */
    int cost();

    /**
     * The id routes name it by: for a registry transport, its constant, or {@code CONSTANT#arm} when
     * the constant has more than one arm.
     */
    String name();

    /**
     * Sets {@link #name()}; the registry calls it once while assembling.
     *
     * @throws UnsupportedOperationException for a transport that is not a registry entry
     */
    default void assignName(String name) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " is not a registry transport");
    }

    /**
     * The arm of its constant this transport is, when it knows ({@code lowToHigh} for a door), or
     * null for the registry to derive. A transport that names its arm keeps it even as its
     * constant's only one.
     */
    default String arm() {
        return null;
    }

    /**
     * The key a {@link NavPolicy} names every arm of this transport's constant by. Defaults to
     * {@link #name()}, right for a constant with a single arm.
     */
    default String costOverrideKey() {
        return name();
    }

    /**
     * The key of this one arm ({@code CONSTANT#lowToHigh}, {@code CONSTANT@x_y}), which is its
     * {@link #name()}; null when the name carries no arm or placement.
     */
    default String armKey() {
        String n = name();
        return n.indexOf('#') >= 0 || n.indexOf('@') >= 0 ? n : null;
    }

    /**
     * The key of its placement, {@code CONSTANT@x_y}: every arm of that object on that tile. Null for
     * a transport not derived from placements of an object.
     */
    default String placementKey() {
        String n = name();
        int at = n.indexOf('@');
        if (at < 0) {
            return null;
        }
        int arm = n.indexOf('#', at);
        return arm < 0 ? n : n.substring(0, arm);
    }

    /**
     * The key of its direction at every placement of its constant, {@code CONSTANT#lowToHigh} for
     * {@code CONSTANT@x_y#lowToHigh}; null when it has no placement or its arm is not a direction.
     */
    default String familyArmKey() {
        String n = name();
        int at = n.indexOf('@');
        int arm = at < 0 ? -1 : n.indexOf('#', at);
        if (arm < 0) {
            return null;
        }
        String direction = n.substring(arm + 1);
        return direction.equals("lowToHigh") || direction.equals("highToLow")
                ? n.substring(0, at) + n.substring(arm)
                : null;
    }

    /**
     * Every key a policy may address it by, in three tiers, most specific first: its arm or name; its
     * placement and its direction everywhere; its whole constant. A tier may be empty. The first tier
     * the policy names decides, and within it a prohibit beats a price. A lift is ignored when any
     * tier prohibits it.
     */
    default List<List<String>> policyKeys() {
        List<String> arm = new ArrayList<>(2);
        for (String k : new String[] {armKey(), name()}) {
            if (k != null && !arm.contains(k)) {
                arm.add(k);
            }
        }
        List<String> placement = new ArrayList<>(2);
        for (String k : new String[] {placementKey(), familyArmKey()}) {
            if (k != null && !arm.contains(k)) {
                placement.add(k);
            }
        }
        String constant = costOverrideKey() == null ? name() : costOverrideKey();
        List<String> whole = arm.contains(constant) || placement.contains(constant) ? List.of() : List.of(constant);
        return List.of(List.copyOf(arm), List.copyOf(placement), whole);
    }

    /**
     * A caveat for people that the gate cannot express, or null. What it requires is read off
     * {@link #prereq()} instead ({@link #requirementText}).
     */
    default String note() {
        return null;
    }

    /** What {@code t} requires, in words: its gate in the world's item names, then its note; null for neither. */
    static String requirementText(Transport t, openrsc.gamedata.defs.ItemDefs items) {
        Prereq gate = t.prereq();
        String derived = gate == Prereq.ALWAYS ? null : gate.display(items);
        String note = t.note();
        if (derived == null) {
            return note;
        }
        return note == null ? derived : derived + " — " + note;
    }

    /**
     * The tiles the server accepts a click on a walk-on scenery object (definition type 2 or 3) from:
     * its footprint, turned by {@code direction}, widened by one tile on the side it faces. Walls do
     * not matter for these objects.
     *
     * @return {@code [minX, maxX, minY, maxY]}, inclusive
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/GameObject.java#L67">GameObject.getObjectBoundary</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L202">Mob.atObject</a>
     */
    static int[] walkOnBoundary(int x, int y, int direction, int defWidth, int defHeight) {
        int w, h;
        if (direction == 0 || direction == 4) {
            w = defWidth;
            h = defHeight;
        } else {
            w = defHeight;
            h = defWidth;
        }
        int minX = x, minY = y;
        switch (direction) {
            case 0 -> {
                minX--;
                w++;
            }
            case 4 -> w++;
            case 2 -> h++;
            case 6 -> {
                minY--;
                h++;
            }
            default -> {}
        }
        return new int[] {minX, minX + w - 1, minY, minY + h - 1};
    }

    /**
     * Every in-bounds tile of {@code rect}, a {@link #walkOnBoundary} result, blocked ones included:
     * the server checks only that the player stands inside it, and a player can stand on a blocked
     * tile another transport lands on.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L203">Mob.atObject</a>
     */
    static List<Point> enumerateWalkOnEntries(CollisionMap map, int[] rect) {
        List<Point> out = new ArrayList<>();
        for (int y = rect[2]; y <= rect[3]; y++) {
            for (int x = rect[0]; x <= rect[1]; x++) {
                if (map.inBounds(x, y)) {
                    out.add(Point.of(x, y));
                }
            }
        }
        return out;
    }

    /**
     * The entries of a one-tile object at {@code (ladderX, ladderY)}: the tile itself when not fully
     * blocked, and each neighbour not fully blocked that the server takes a click on it from
     * ({@link CollisionMap#canReachScenery}), judged on the static map.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L205">Mob.atObject</a>
     */
    static List<Point> enumerateEntries(CollisionMap map, int ladderX, int ladderY) {
        List<Point> out = new ArrayList<>(9);
        if (map.inBounds(ladderX, ladderY) && !map.fullyBlocked(ladderX, ladderY)) {
            out.add(Point.of(ladderX, ladderY));
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int nx = ladderX + dx, ny = ladderY + dy;
                if (!map.inBounds(nx, ny)) {
                    continue;
                }
                if (map.fullyBlocked(nx, ny)) {
                    continue;
                }
                if (!map.canReachScenery(nx, ny, ladderX, ladderX, ladderY, ladderY, null)) {
                    continue;
                }
                out.add(Point.of(nx, ny));
            }
        }
        return out;
    }

    /**
     * The entries of a {@code w} x {@code h} object anchored at {@code (x, y)}, already turned by its
     * direction: each tile in or around it, not fully blocked, that the server takes a click on the
     * footprint from ({@link CollisionMap#canReachScenery}).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L205">Mob.atObject</a>
     */
    static List<Point> enumerateEntriesRect(CollisionMap map, int x, int y, int w, int h) {
        List<Point> out = new ArrayList<>(2 * (w + h) + w * h);
        int maxX = x + w - 1, maxY = y + h - 1;
        for (int ny = y - 1; ny <= maxY + 1; ny++) {
            for (int nx = x - 1; nx <= maxX + 1; nx++) {
                if (!map.inBounds(nx, ny)) {
                    continue;
                }
                if (map.fullyBlocked(nx, ny)) {
                    continue;
                }
                if (map.canReachScenery(nx, ny, x, maxX, y, maxY, null)) {
                    out.add(Point.of(nx, ny));
                }
            }
        }
        return out;
    }

    /**
     * The entries of one object of definition size {@code width} x {@code height} placed with
     * {@code direction}: {@link #enumerateEntries} for one tile, else {@link #enumerateEntriesRect}
     * over the turned footprint. For an obstacle placed as several one-tile objects, call
     * {@link #enumerateEntries} per object instead.
     */
    static List<Point> entriesFor(CollisionMap map, int ex, int ey, int width, int height, int direction) {
        if (width * height <= 1) {
            return enumerateEntries(map, ex, ey);
        }
        boolean swap = direction != 0 && direction != 4;
        int fw = swap ? height : width;
        int fh = swap ? width : height;
        return enumerateEntriesRect(map, ex, ey, fw, fh);
    }
}
