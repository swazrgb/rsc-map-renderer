package openrsc.nav.transports;

import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * Operate a scenery object, or use an item on it, and be moved to a fixed tile from whichever entry
 * fires it: the shape of every climb, obstacle and portal whose handler hard-codes the landing.
 * Optionally answers the menus the click opens, or lands in the player's column as a plain ladder
 * does ({@link #coordPreserving}).
 */
public final class ScriptedLadderTransport implements Transport, DeclarativeTransport {

    private String name;
    private String placementId;

    private final int objectId;
    private final int objectX, objectY;
    private final int destX, destY;
    private final List<Point> entries;
    private final Prereq prereq;

    private final int cost;
    private String note;
    private String[][] menuAnswers = new String[0][];
    private boolean coordPreserving;

    private boolean coordPreservingUp;
    private int useItemId = -1;
    private List<Consumed> consumed = List.of();
    private boolean command2;

    public ScriptedLadderTransport(
            int objectId,
            int objectX,
            int objectY,
            int destX,
            int destY,
            Prereq prereq,
            int cost,
            List<Point> entries) {
        this.objectId = objectId;
        this.objectX = objectX;
        this.objectY = objectY;
        this.destX = destX;
        this.destY = destY;
        this.prereq = prereq;
        this.cost = cost;
        this.entries = List.copyOf(entries);
    }

    public ScriptedLadderTransport withNote(String note) {
        this.note = note;
        return this;
    }

    /** One keyword group per menu the click opens, each answered by the first group that matches. */
    public ScriptedLadderTransport withDialog(String[]... menuAnswers) {
        this.menuAnswers = menuAnswers;
        return this;
    }

    public ScriptedLadderTransport usingCommand2() {
        this.command2 = true;
        return this;
    }

    /**
     * Lands each entry {@code (x, y)} on {@code (x, LadderTransport.newY(y, up))} instead of the fixed
     * tile, for a scripted ladder that climbs as the default one does. Returns {@code this}.
     */
    public ScriptedLadderTransport coordPreserving(boolean up) {
        this.coordPreserving = true;
        this.coordPreservingUp = up;
        return this;
    }

    /** Fires by using {@code itemId} on the object instead of operating it. Returns {@code this}. */
    public ScriptedLadderTransport usingItem(int itemId) {
        this.useItemId = itemId;
        return this;
    }

    public ScriptedLadderTransport consuming(int itemId, int count) {
        this.consumed = List.of(new Consumed(ItemId.getById(itemId), count));
        return this;
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
        return Point.of(objectX, objectY);
    }

    @Override
    public List<Point> entries() {
        return entries;
    }

    @Override
    public Point destinationFrom(Point from) {
        if (coordPreserving) {
            return Point.of(from.x(), LadderTransport.newY(from.y(), coordPreservingUp));
        }
        return Point.of(destX, destY);
    }

    @Override
    public boolean landingDependsOnEntry() {
        return coordPreserving;
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

    public ScriptedLadderTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return consumed;
    }

    @Override
    public String note() {
        return note;
    }

    public int objectId() {
        return objectId;
    }

    public int objectX() {
        return objectX;
    }

    public int objectY() {
        return objectY;
    }

    @Override
    public EngageSpec engageSpec() {
        EngageSpec.Action action = useItemId >= 0
                ? EngageSpec.Action.useItem(useItemId)
                : command2 ? EngageSpec.Action.INTERACT_2 : EngageSpec.Action.INTERACT_1;
        return new EngageSpec(
                action,
                new EngageSpec.Target.Scenery(objectId, objectX, objectY),
                Arrays.asList(menuAnswers),
                menuAnswers.length > 0,
                null);
    }
}
