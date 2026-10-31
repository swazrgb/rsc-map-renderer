package openrsc.nav.transports;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * One direction of a declared door or gate: from one flank to the other, by the click its
 * {@link EngageSpec} names. Built by {@link openrsc.nav.transports.spec.Door}, one per arm.
 */
public final class DoorTransport implements Transport, DeclarativeTransport {

    private final String placement;
    private final String arm;
    private final List<Consumed> consumes;
    private final Point scenery;

    private final int entryX, entryY;
    private final int destX, destY;
    private final Prereq prereq;

    private String name;
    private final String note;
    private final int cost;
    private final EngageSpec spec;

    /**
     * {@code placement} is the declaring constant, which both arms of a door answer to in a cost
     * override; {@code arm} is this arm's key, {@code CONSTANT#arm}. {@code scenery} is a scenery
     * gate's tile, {@code null} for a boundary door.
     */
    public DoorTransport(
            String placement,
            String arm,
            EngageSpec spec,
            Point scenery,
            Point from,
            Point to,
            Prereq prereq,
            String note,
            int cost,
            List<Consumed> consumes) {
        this.placement = placement;
        this.arm = arm;
        this.scenery = scenery;
        this.entryX = from.x();
        this.entryY = from.y();
        this.destX = to.x();
        this.destY = to.y();
        this.prereq = prereq;
        this.note = note;
        this.cost = cost;
        this.spec = spec;
        this.consumes = List.copyOf(consumes);
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == entryX && y == entryY;
    }

    @Override
    public EngageSpec.Target.Boundary boundary() {
        return spec.target() instanceof EngageSpec.Target.Boundary b ? b : null;
    }

    @Override
    public boolean landingDependsOnEntry() {
        EngageSpec.Target.Boundary b = boundary();
        // A diagonal boundary is clicked from anywhere in the 3x3 around it, but moves the player only
        // from the four tiles beside it:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/GameObject.java#L98
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1724
        return b != null && (b.dir() == 2 || b.dir() == 3);
    }

    @Override
    public Point sceneryAnchor() {
        return scenery;
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
    public List<Consumed> consumes(openrsc.bot.api.eligibility.AccountFacts account) {
        return consumes;
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
    public String note() {
        return note;
    }

    @Override
    public String costOverrideKey() {
        return placement;
    }

    @Override
    public String arm() {
        return arm.substring(arm.indexOf('#') + 1);
    }

    @Override
    public EngageSpec engageSpec() {
        return spec;
    }
}
