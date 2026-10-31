package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.DoorTransport;
import openrsc.nav.transports.EngageSpec;
import openrsc.nav.transports.spec.Rows.Answers;
import openrsc.nav.transports.spec.Rows.ArmCost;
import openrsc.nav.transports.spec.Rows.LandsAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.OpenedWith;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.Pays;

/**
 * A door or gate opened and stepped through, from one flank to the other. Its two arms are named by
 * coordinate: {@code lowToHigh} steps from the flank with the smaller coordinate to the larger,
 * {@code highToLow} back. An arm gated {@link Prereq#NEVER} is not declared.
 *
 * <p>A boundary's dir 0 runs between (x, z-1) and (x, z), dir 1 between (x-1, z) and (x, z); a
 * diagonal boundary fills its tile, and is crossed over it from (x, z-1) to (x, z+1) and back. A
 * scenery gate's dir 0 runs between (x-1, z) and (x, z), 2 between (x, z) and (x, z+1), 4 between
 * (x, z) and (x+1, z), 6 between (x, z-1) and (x, z).
 *
 * <p>An arm whose gate asks for the {@code openedWith} key uses the key on the door; any other arm
 * clicks. An arm spends the {@code pays} items its own gate asks for, and no others.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1710">Functions.doDoor</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1819">Functions.doGate</a>
 */
public record Door(
        boolean boundary,
        int objectId,
        int x,
        int z,
        int dir,
        Prereq lowToHigh,
        Prereq highToLow,
        boolean symmetric,
        int keyItem,
        List<Pays> spends,
        Point lowToHighLands,
        Point highToLowLands,
        int lowToHighCost,
        int highToLowCost,
        List<String[]> dialog,
        String note)
        implements TransportSpec {

    /** A door's cost, in the planner's units of 10 per walked tile. */
    public static final int COST = 30;

    // Thieving's lock-picked doors: from the locked side the first command only says the door is
    // locked, and the second picks the lock.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/thieving/Thieving.java#L640
    private static final Set<Integer> PICKLOCK_BOUNDARIES = Set.of(93, 94, 95, 96, 97, 99, 100, 162, 169);

    public static Door door(int id, int x, int z, int dir, Prereq gate, Option... options) {
        return make(true, id, x, z, dir, gate, gate, options);
    }

    public static Door door(int id, int x, int z, int dir, Prereq lowToHigh, Prereq highToLow, Option... options) {
        return make(true, id, x, z, dir, lowToHigh, highToLow, options);
    }

    public static Door sceneryDoor(int id, int x, int z, int dir, Prereq gate, Option... options) {
        return make(false, id, x, z, dir, gate, gate, options);
    }

    public static Door sceneryDoor(
            int id, int x, int z, int dir, Prereq lowToHigh, Prereq highToLow, Option... options) {
        return make(false, id, x, z, dir, lowToHigh, highToLow, options);
    }

    /** Every placement of boundary door {@code id}, for a handler that opens it by id alone. */
    public static EveryPlacement everyDoor(int id, Prereq gate, Option... options) {
        return EveryPlacement.everyBoundary(id, p -> List.of(door(id, p.x(), p.z(), p.dir(), gate, options)));
    }

    /** Every placement of scenery gate {@code id}, for a handler that opens it by id alone. */
    public static EveryPlacement everySceneryDoor(int id, Prereq gate, Option... options) {
        return EveryPlacement.everyScenery(id, p -> List.of(sceneryDoor(id, p.x(), p.z(), p.dir(), gate, options)));
    }

    /**
     * A handler's test of where the player stands, at placement {@code p}, for a player on the flank
     * {@code from}.
     */
    @FunctionalInterface
    public interface Side {
        boolean test(EveryPlacement.Placed p, Point from);
    }

    /**
     * Every placement of boundary door {@code id}, each arm gated by {@code ifSide} where {@code side}
     * holds of the flank it steps from and by {@code otherwise} where it does not.
     */
    public static EveryPlacement everyDoorBySide(
            int id, Side side, Prereq ifSide, Prereq otherwise, Option... options) {
        return EveryPlacement.everyBoundary(id, p -> bySide(true, p, side, ifSide, otherwise, options));
    }

    public static EveryPlacement everySceneryDoorBySide(
            int id, Side side, Prereq ifSide, Prereq otherwise, Option... options) {
        return EveryPlacement.everyScenery(id, p -> bySide(false, p, side, ifSide, otherwise, options));
    }

    /** The door {@link #everyDoorBySide} makes of placement {@code p}; empty for a facing with no flanks. */
    public static List<Door> bySide(
            boolean boundary, EveryPlacement.Placed p, Side side, Prereq ifSide, Prereq otherwise, Option... options) {
        Point low = lowFlank(boundary, p.x(), p.z(), p.dir());
        Point high = highFlank(boundary, p.x(), p.z(), p.dir());
        if (low == null || high == null) {
            return List.of();
        }
        Prereq up = side.test(p, low) ? ifSide : otherwise;
        Prereq down = side.test(p, high) ? ifSide : otherwise;
        return List.of(make(boundary, p.objectId(), p.x(), p.z(), p.dir(), up, down, options));
    }

    private static Door make(
            boolean boundary, int id, int x, int z, int dir, Prereq lowToHigh, Prereq highToLow, Option... options) {
        allowOnly(
                "a door",
                options,
                OpenedWith.class,
                Pays.class,
                LandsAt.class,
                ArmCost.class,
                Answers.class,
                Note.class);
        OpenedWith key = Rows.find(OpenedWith.class, options);
        Point up = null;
        Point down = null;
        int upCost = COST;
        int downCost = COST;
        for (Option o : options) {
            if (o instanceof LandsAt l) {
                if (l.lowToHigh()) {
                    up = l.tile();
                } else {
                    down = l.tile();
                }
            } else if (o instanceof ArmCost c) {
                if (c.lowToHigh()) {
                    upCost = c.cost();
                } else {
                    downCost = c.cost();
                }
            }
        }
        return new Door(
                boundary,
                id,
                x,
                z,
                dir,
                lowToHigh,
                highToLow,
                lowToHigh == highToLow,
                key == null ? -1 : key.itemId(),
                Rows.allPaysOf(options),
                up,
                down,
                upCost,
                downCost,
                List.of(Rows.answersOf(options)),
                Rows.noteOf(options));
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        Point low = lowFlank();
        Point high = highFlank();
        if (low == null || high == null) {
            return List.of();
        }
        List<Transport> out = new ArrayList<>(2);
        arm(out, id, true, low, lowToHighLands != null ? lowToHighLands : high, lowToHigh, lowToHighCost);
        arm(out, id, false, high, highToLowLands != null ? highToLowLands : low, highToLow, highToLowCost);
        return out;
    }

    private void arm(List<Transport> out, String id, boolean lowToHigh, Point from, Point to, Prereq gate, int cost) {
        if (gate == Prereq.NEVER) {
            return;
        }
        boolean keyUse = keyItem >= 0 && requiresCarrying(gate, keyItem);
        EngageSpec.Action action = keyUse
                ? EngageSpec.Action.useItem(keyItem)
                : boundary && PICKLOCK_BOUNDARIES.contains(objectId) && gate != Prereq.ALWAYS
                        ? EngageSpec.Action.INTERACT_2
                        : EngageSpec.Action.INTERACT_1;
        EngageSpec.Target target = boundary
                ? new EngageSpec.Target.Boundary(objectId, x, z, dir)
                : new EngageSpec.Target.Scenery(objectId, x, z);
        List<Transport.Consumed> spent = new ArrayList<>();
        for (Pays p : spends) {
            if (requiresCarrying(gate, p.itemId())) {
                spent.add(new Transport.Consumed(ItemId.getById(p.itemId()), p.count()));
            }
        }
        out.add(new DoorTransport(
                id,
                openrsc.nav.transports.SpecialDoor.armKey(id, lowToHigh),
                dialog.isEmpty() ? EngageSpec.of(action, target) : EngageSpec.of(action, target, dialog),
                boundary ? null : Point.of(x, z),
                from,
                to,
                gate,
                note,
                cost,
                spent));
    }

    private static boolean requiresCarrying(Prereq gate, int itemId) {
        for (Fact f : gate.facts()) {
            if (f.kind() == Fact.Kind.CARRIED_AT_LEAST && f.itemId() == itemId) {
                return true;
            }
        }
        return false;
    }

    private Point lowFlank() {
        return lowFlank(boundary, x, z, dir);
    }

    private Point highFlank() {
        return highFlank(boundary, x, z, dir);
    }

    /**
     * The flank a {@code lowToHigh} arm steps from, of a boundary when {@code boundary} and else of a
     * scenery gate; {@code null} for a facing no door crosses.
     */
    public static Point lowFlank(boolean boundary, int x, int z, int dir) {
        if (boundary) {
            return switch (dir) {
                case 0, 2, 3 -> Point.of(x, z - 1);
                case 1 -> Point.of(x - 1, z);
                default -> null;
            };
        }
        return switch (dir) {
            case 0 -> Point.of(x - 1, z);
            case 2, 4 -> Point.of(x, z);
            case 6 -> Point.of(x, z - 1);
            default -> null;
        };
    }

    /** The flank a {@code highToLow} arm steps from; see {@link #lowFlank}. */
    public static Point highFlank(boolean boundary, int x, int z, int dir) {
        if (boundary) {
            return switch (dir) {
                case 0, 1 -> Point.of(x, z);
                case 2, 3 -> Point.of(x, z + 1);
                default -> null;
            };
        }
        return switch (dir) {
            case 0, 6 -> Point.of(x, z);
            case 2 -> Point.of(x, z + 1);
            case 4 -> Point.of(x + 1, z);
            default -> null;
        };
    }
}
