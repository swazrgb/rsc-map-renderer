package openrsc.nav.transports;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * One direction across one jungle vine: click it until the chop succeeds. The chop removes the vine
 * for 5.5 seconds and moves the player onto the vine's tile; a crossing that lands beyond that tile
 * is finished by walking on through the gap. See {@link openrsc.nav.transports.spec.CutBoundary} for
 * who may chop.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L108">WoodcutJungle.cutJungle</a>
 */
public final class JungleVineTransport implements Transport, DeclarativeTransport {

    public static final int VINE_ID = 204;

    private String name;
    private final String placementId;

    private final int vineX, vineY, vineDir;
    private final int entryX, entryY;
    private final int destX, destY;
    private final Prereq prereq;

    private final int cost;

    public JungleVineTransport(
            String placementId,
            int vineX,
            int vineY,
            int vineDir,
            int entryX,
            int entryY,
            int destX,
            int destY,
            boolean stepThroughVineTile,
            Prereq prereq,
            int cost) {
        this.placementId = placementId;
        this.vineX = vineX;
        this.vineY = vineY;
        this.vineDir = vineDir;
        this.entryX = entryX;
        this.entryY = entryY;
        this.destX = destX;
        this.destY = destY;
        this.prereq = prereq;
        this.cost = cost;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == entryX && y == entryY;
    }

    @Override
    public EngageSpec.Target.Boundary boundary() {
        return new EngageSpec.Target.Boundary(VINE_ID, vineX, vineY, vineDir);
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(entryX, entryY));
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
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

    @Override
    public String costOverrideKey() {
        return placementId;
    }

    @Override
    public EngageSpec engageSpec() {
        return EngageSpec.of(EngageSpec.Action.INTERACT_1, boundary());
    }
}
