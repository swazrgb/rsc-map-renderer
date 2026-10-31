package openrsc.nav.transports;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * Click a scenery object from one entry tile, or use an item on it, and be moved to a fixed tile.
 * The object is anchored on the entry tile until {@link #atScenery} places it.
 */
public final class ObjectInteractTransport implements Transport, DeclarativeTransport {

    private String name;
    private String placementId;

    private final int objectId;
    private final int entryX, entryY;
    private int sceneryX, sceneryY;
    private final int destX, destY;
    private final Prereq prereq;

    private final int cost;
    private String note;
    private boolean awaitsDialog;
    private int useItemId = -1;
    private List<Consumed> consumed = List.of();
    private boolean anchorPinned;

    public ObjectInteractTransport(
            int objectId, int entryX, int entryY, int destX, int destY, Prereq prereq, int cost) {
        this.objectId = objectId;
        this.entryX = entryX;
        this.entryY = entryY;
        this.sceneryX = entryX;
        this.sceneryY = entryY;
        this.destX = destX;
        this.destY = destY;
        this.prereq = prereq;
        this.cost = cost;
    }

    /** Places the object on {@code (x, y)} and marks the anchor {@link #anchorPinned()}. */
    public ObjectInteractTransport atScenery(int x, int y) {
        this.sceneryX = x;
        this.sceneryY = y;
        this.anchorPinned = true;
        return this;
    }

    /** Other objects the anchor may hold that the same click crosses; see {@link EngageSpec.Target.Scenery}. */
    public ObjectInteractTransport orScenery(int... ids) {
        List<Integer> out = new java.util.ArrayList<>();
        for (int id : ids) {
            out.add(id);
        }
        this.alsoIds = List.copyOf(out);
        return this;
    }

    private List<Integer> alsoIds = List.of();

    /** Marks a click that starts a conversation before the move; see {@link EngageSpec#awaitsDialog()}. */
    public ObjectInteractTransport awaitsDialog() {
        this.awaitsDialog = true;
        return this;
    }

    public ObjectInteractTransport usingItem(int itemId) {
        this.useItemId = itemId;
        return this;
    }

    public ObjectInteractTransport consuming(int itemId, int count) {
        this.consumed = List.of(new Consumed(ItemId.getById(itemId), count));
        return this;
    }

    public ObjectInteractTransport withNote(String note) {
        this.note = note;
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

    public ObjectInteractTransport withPlacement(String id) {
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
    public List<Consumed> consumes(AccountFacts account) {
        return consumed;
    }

    public boolean anchorPinned() {
        return anchorPinned;
    }

    public int objectId() {
        return objectId;
    }

    public int sceneryAnchorX() {
        return sceneryX;
    }

    public int sceneryAnchorY() {
        return sceneryY;
    }

    public int entryX() {
        return entryX;
    }

    public int entryY() {
        return entryY;
    }

    @Override
    public EngageSpec engageSpec() {
        EngageSpec.Action action = useItemId >= 0 ? EngageSpec.Action.useItem(useItemId) : EngageSpec.Action.INTERACT_1;
        return new EngageSpec(
                action,
                new EngageSpec.Target.Scenery(objectId, sceneryX, sceneryY, alsoIds),
                List.of(),
                awaitsDialog,
                null);
    }
}
