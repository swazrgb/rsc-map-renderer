package openrsc.nav.transports;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs;
import openrsc.nav.eligibility.Prereq;

/**
 * A way off Karamja for a player too poor for the ships' {@link #FARE}-coin fare: earn it on Luthas's
 * banana plantation, played by a hand-written script. Luthas pays {@link #FARE} coins for a crate
 * filled with {@link #CRATE_CAPACITY} bananas, as often as asked. The transport never moves the
 * player itself: once the fare is carried, the ships are eligible and far cheaper, and the next plan
 * takes one. Its destination is where that ship would land, one transport per ship.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/PiratesTreasure.java#L79">PiratesTreasure.onUseLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/PiratesTreasure.java#L275">PiratesTreasure.luthasDialogue</a>
 */
public final class BananaPlantationTransport implements Transport, DeclarativeTransport {

    private String placementId;

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.BANANA_PLANTATION);

    public static final int COINS = ItemId.COINS.id();
    public static final int BANANA = ItemId.BANANA.id();
    public static final int LUTHAS = NpcId.LUTHAS.id();
    public static final int BANANA_TREE = 183;
    public static final int CRATE_ID = 182;
    public static final int CRATE_X = 337, CRATE_Y = 711;

    public static final int FARE = 30;

    // A last resort beside any real route, yet far below the engine's unreachable weight, so that a
    // route through it still sums to a finite cost.
    private static final int COST = 1_000_000;

    public static final int CRATE_CAPACITY = 10;

    private static final List<Point> ENTRY =
            List.of(Point.of(339, 706), Point.of(338, 706), Point.of(340, 707), Point.of(339, 708));

    private String name;
    private final int destX, destY;

    /** The way off the island that ends where a ship lands on {@code (destX, destY)}. */
    public BananaPlantationTransport(int destX, int destY) {
        this.destX = destX;
        this.destY = destY;
    }

    @Override
    public Prereq prereq() {
        // Only while the fare is not carried: for a player who has it, the ships are the way.
        return Prereq.fact(EligibilityInputs.Fact.notCarrying(ItemId.COINS.id(), FARE - 1));
    }

    @Override
    public boolean isEntry(int x, int y) {
        for (Point e : ENTRY) {
            if (e.x() == x && e.y() == y) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return ENTRY;
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        // It earns the coins the ship then takes, so nothing needs funding beforehand.
        return List.of();
    }

    @Override
    public int cost() {
        return COST;
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    public BananaPlantationTransport withPlacement(String id) {
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
        return "earns the " + FARE + "-coin fare picking bananas for Luthas";
    }
}
