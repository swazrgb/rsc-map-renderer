package openrsc.nav.transports;

import java.util.List;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.SceneryId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * The Shantay Pass stone gate out of the desert: from south of it, a click moves the player to the
 * north side, free. Carrying Ana in a barrel, the click runs a Tourist Trap scene instead, so the
 * crossing is gated on not carrying her.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L2601">TouristTrap.onOpLoc</a>
 */
public final class ShantayPassReturnTransport implements Transport, DeclarativeTransport {

    private String placementId;

    private static final int STONE_GATE_ID = SceneryId.STONE_GATE.id();
    private static final int ANA_IN_A_BARREL_ITEM_ID = ItemId.ANA_IN_A_BARREL.id();

    public static final int GATE_X = 62;

    public static final int GATE_Y = 733;

    private static final int[] ENTRY_X = {GATE_X, GATE_X + 1};

    public static final int ENTRY_Y = GATE_Y + 2;

    public static final int DEST_X = 62;

    public static final int DEST_Y = 732;

    @Override
    public boolean isEntry(int x, int y) {
        if (y != ENTRY_Y) {
            return false;
        }
        for (int ex : ENTRY_X) {
            if (ex == x) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(ENTRY_X[0], ENTRY_Y), Point.of(ENTRY_X[1], ENTRY_Y));
    }

    @Override
    public Point sceneryAnchor() {
        return Point.of(GATE_X, GATE_Y);
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(DEST_X, DEST_Y);
    }

    @Override
    public Prereq prereq() {
        return Prereq.fact(Fact.notCarrying(ANA_IN_A_BARREL_ITEM_ID));
    }

    @Override
    public int cost() {
        return 5;
    }

    public ShantayPassReturnTransport withPlacement(String id) {
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
    public EngageSpec engageSpec() {
        return EngageSpec.of(
                EngageSpec.Action.INTERACT_1, new EngageSpec.Target.Scenery(STONE_GATE_ID, GATE_X, GATE_Y));
    }
}
