package openrsc.nav.transports;

import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * Click a scenery object from one entry tile, answer the menus the click opens, and be moved to a
 * fixed tile. The object stands on {@code (sceneryX, sceneryY)}, which need not be walkable.
 */
public final class ObjectMenuTransport implements Transport, DeclarativeTransport {

    private String name;
    private String placementId;

    private final int objectId;
    private final int sceneryX, sceneryY;
    private final int entryX, entryY;
    private final int destX, destY;
    private final String[][] menuAnswers;
    private final Prereq prereq;

    private final int cost;
    private String note;
    private List<Consumed> consumes = List.of();
    private boolean command2 = false;

    public ObjectMenuTransport(
            int objectId,
            int sceneryX,
            int sceneryY,
            int entryX,
            int entryY,
            int destX,
            int destY,
            String[][] menuAnswers,
            Prereq prereq,
            int cost,
            String note) {
        this.objectId = objectId;
        this.sceneryX = sceneryX;
        this.sceneryY = sceneryY;
        this.entryX = entryX;
        this.entryY = entryY;
        this.destX = destX;
        this.destY = destY;
        this.menuAnswers = menuAnswers;
        this.prereq = prereq;
        this.cost = cost;
        this.note = note;
    }

    public ObjectMenuTransport consuming(int itemId, int count) {
        this.consumes = List.of(new Consumed(ItemId.getById(itemId), count));
        return this;
    }

    /** Clicks the object's second command instead of its first. Returns {@code this}. */
    public ObjectMenuTransport useCommand2() {
        this.command2 = true;
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
    public Point sceneryAnchor() {
        return Point.of(sceneryX, sceneryY);
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
    public List<Consumed> consumes(AccountFacts account) {
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

    public ObjectMenuTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public EngageSpec engageSpec() {
        return EngageSpec.of(
                command2 ? EngageSpec.Action.INTERACT_2 : EngageSpec.Action.INTERACT_1,
                new EngageSpec.Target.Scenery(objectId, sceneryX, sceneryY),
                Arrays.asList(menuAnswers));
    }
}
