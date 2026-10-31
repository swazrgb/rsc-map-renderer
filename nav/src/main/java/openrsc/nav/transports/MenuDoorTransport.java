package openrsc.nav.transports;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * One direction of a boundary door that moves the player across only after they answer the menus
 * its click opens, from one entry tile to a fixed landing.
 */
public final class MenuDoorTransport implements Transport, DeclarativeTransport {

    private String placementId;

    /** One menu: answered by the option that matches any of {@code keywords}. */
    public record MenuStep(String... keywords) {}

    private String name;
    private final int boundaryId, wallX, wallY, dir;
    private final int entryX, entryY;
    private final int destX, destY;
    private final List<MenuStep> steps;
    private boolean sequenced;
    private final Prereq prereq;

    private final int cost;
    private final String note;
    private List<Consumed> consumes = List.of();

    public MenuDoorTransport(
            int boundaryId,
            int wallX,
            int wallY,
            int dir,
            int entryX,
            int entryY,
            int destX,
            int destY,
            List<MenuStep> steps,
            Prereq prereq,
            int cost,
            String note) {
        this.boundaryId = boundaryId;
        this.wallX = wallX;
        this.wallY = wallY;
        this.dir = dir;
        this.entryX = entryX;
        this.entryY = entryY;
        this.destX = destX;
        this.destY = destY;
        this.steps = List.copyOf(steps);
        this.prereq = prereq;
        this.cost = cost;
        this.note = note;
    }

    public MenuDoorTransport consuming(int itemId, int count) {
        this.consumes = List.of(new Consumed(ItemId.getById(itemId), count));
        return this;
    }

    /** Answers menu N with step N, for menus whose options repeat. Returns {@code this}. */
    public MenuDoorTransport sequenced() {
        this.sequenced = true;
        return this;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == entryX && y == entryY;
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(entryX, entryY));
    }

    @Override
    public EngageSpec.Target.Boundary boundary() {
        return new EngageSpec.Target.Boundary(boundaryId, wallX, wallY, dir);
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

    public MenuDoorTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name();
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
    public List<Consumed> consumes(AccountFacts account) {
        return consumes;
    }

    @Override
    public EngageSpec engageSpec() {
        List<String[]> dialog = new ArrayList<>(steps.size());
        for (MenuStep step : steps) {
            dialog.add(step.keywords());
        }
        EngageSpec.Target target = boundary();
        return sequenced
                ? EngageSpec.sequence(EngageSpec.Action.INTERACT_1, target, dialog)
                : EngageSpec.of(EngageSpec.Action.INTERACT_1, target, dialog);
    }
}
