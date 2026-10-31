package openrsc.nav.transports;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * A one-tile ladder that keeps the player's column: from an entry {@code (x, y)} it lands on
 * {@code (x, newY(y, up))}, so each entry has its own landing.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L287">Ladders.coordModifier</a>
 */
public final class LadderTransport implements Transport, DeclarativeTransport {

    private String name;
    private String placementId;

    private final int objectId;
    private final int ladderX, ladderY;
    private final boolean up;
    private final List<Point> entries;
    private final Prereq prereq;

    private final int cost;

    public LadderTransport(
            int objectId, int ladderX, int ladderY, boolean up, List<Point> entries, Prereq prereq, int cost) {
        this.objectId = objectId;
        this.ladderX = ladderX;
        this.ladderY = ladderY;
        this.up = up;
        this.entries = List.copyOf(entries);
        this.prereq = prereq;
        this.cost = cost;
    }

    @Override
    public boolean isEntry(int x, int y) {
        for (Point e : entries) {
            if (e.x() == x && e.y() == y) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Point sceneryAnchor() {
        return Point.of(ladderX, ladderY);
    }

    @Override
    public List<Point> entries() {
        return entries;
    }

    @Override
    public Point destinationFrom(Point from) {
        int dy = newY(from.y(), up);
        return Point.of(from.x(), dy);
    }

    @Override
    public boolean landingDependsOnEntry() {
        return true;
    }

    @Override
    public Prereq prereq() {
        return prereq;
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    public LadderTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }

    public int objectId() {
        return objectId;
    }

    @Override
    public EngageSpec engageSpec() {
        return EngageSpec.of(EngageSpec.Action.INTERACT_1, new EngageSpec.Target.Scenery(objectId, ladderX, ladderY));
    }

    /**
     * The y one floor {@code up} or down from {@code y}, floors being {@code y / 944} with 3 the
     * basement; {@code y} itself where the server refuses the climb (up from floor 2, down from the
     * basement).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L504">Formulae.getNewY</a>
     */
    public static int newY(int y, boolean up) {
        int floor = y / 944;
        int yLocal = y % 944;
        int newFloor;
        if (up) {
            if (floor == 3) {
                newFloor = 0;
            } else if (floor >= 2) {
                return y;
            } else {
                newFloor = floor + 1;
            }
        } else {
            if (floor == 0) {
                newFloor = 3;
            } else if (floor >= 3) {
                return y;
            } else {
                newFloor = floor - 1;
            }
        }
        return newFloor * 944 + yLocal;
    }
}
