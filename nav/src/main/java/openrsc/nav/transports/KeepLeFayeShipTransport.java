package openrsc.nav.transports;

import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.data.SceneryId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * Stowing away on the Catherby merchant ship into Keep Le Faye, played by a hand-written script from
 * the dock tile. The server refuses before Merlin's Crystal stage 2, and while Arhein stands within 9
 * tiles and is not busy; the crossing therefore waits on another player keeping him busy, which no
 * gate can state. Once aboard the player lands inside the keep.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/MerlinsCrystal.java#L81">MerlinsCrystal.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L616">Functions.ifnearvisnpc</a>
 */
public final class KeepLeFayeShipTransport implements Transport, DeclarativeTransport {

    private String placementId;

    public static final int DOCK_X = 439, DOCK_Z = 504;
    public static final int LANDING_X = 456, LANDING_Z = 520;
    private static final int SHIP_X = 436, SHIP_Z = 504;

    // High, for the wait on a second player that no gate shows.
    private static final int COST = 900;

    @Override
    public Prereq prereq() {
        return Prereq.fact(Fact.questAtLeast(QuestId.MERLINS_CRYSTAL.id(), 2));
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == DOCK_X && y == DOCK_Z;
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(DOCK_X, DOCK_Z));
    }

    @Override
    public Point sceneryAnchor() {
        return Point.of(SHIP_X, SHIP_Z);
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(LANDING_X, LANDING_Z);
    }

    @Override
    public int cost() {
        return COST;
    }

    public KeepLeFayeShipTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name();
    }

    private String name;

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
        return "needs a second bot holding Arhein's menu";
    }

    /** The ship scenery the script clicks to stow away. */
    public static final int STERN_ID = SceneryId.SHIP_BACK_MERCHANT.id();

    @Override
    public EngageSpec engageSpec() {
        return EngageSpec.bespoke(EngageSpec.BespokeScript.KEEP_LE_FAYE_SHIP);
    }
}
