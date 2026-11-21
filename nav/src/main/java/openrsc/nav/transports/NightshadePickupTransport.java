package openrsc.nav.transports;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * The way into the Ogre Enclave for a player carrying no nightshade: take one off its spawn in a
 * Skavid cave, played by a hand-written script. The transport never moves the player itself: once a
 * nightshade is carried the guard's own crossing is eligible, and the next plan walks out of the cave
 * to him. Its destination is where the guard lands the player, one transport per spawn.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerMechanism.java#L308">WatchTowerMechanism.onUseNpc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/conf/server/defs/locs/GroundItems.json">GroundItems.json</a>
 */
public final class NightshadePickupTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.NIGHTSHADE_PICKUP);

    public static final int NIGHTSHADE = ItemId.NIGHTSHADE.id();

    /** The spawn in the cave the scenery 970 entrances lead to. */
    public static final Point NORTH_SPAWN = Point.of(650, 3560);

    /** The spawn in the cave the scenery 975 entrance leads to. */
    public static final Point SOUTH_SPAWN = Point.of(650, 3592);

    /** Where the guard lands whoever hands him a nightshade. */
    public static final Point ENCLAVE = Point.of(647, 3644);

    /** A spawn's 45-second respawn, in deci-ticks: what each taker ahead adds to the wait. */
    public static final int RESPAWN_COST = 700;

    /** Half a respawn, the wait at a spawn nobody else is taking from. */
    static final int WAIT_COST = RESPAWN_COST / 2;

    private static final int NORTH_ONWARD = 1520;
    private static final int SOUTH_ONWARD = 1540;

    /** The wait, the walk from the north spawn out of its cave to the guard, and his crossing. */
    public static final int NORTH_COST = WAIT_COST + NORTH_ONWARD;

    /** The wait, the walk from the south spawn out of its cave to the guard, and his crossing. */
    public static final int SOUTH_COST = WAIT_COST + SOUTH_ONWARD;

    private final Point spawn;
    private final int cost;
    private String placementId;
    private String name;

    public NightshadePickupTransport(Point spawn, int cost) {
        this.spawn = spawn;
        this.cost = cost;
    }

    /** The tile the nightshade respawns on. */
    public Point spawn() {
        return spawn;
    }

    @Override
    public Prereq prereq() {
        // Only while none is carried: for a player who has one, the guard is the way.
        return Prereq.all(
                Prereq.fact(Fact.questComplete(QuestId.WATCHTOWER.id())),
                Prereq.fact(Fact.notCarrying(NIGHTSHADE)),
                Prereq.fact(Fact.roomFor(NIGHTSHADE, 1)));
    }

    @Override
    public boolean isEntry(int x, int y) {
        return spawn.x() == x && spawn.y() == y;
    }

    @Override
    public List<Point> entries() {
        return List.of(spawn);
    }

    @Override
    public Point destinationFrom(Point from) {
        return ENCLAVE;
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        // It takes the nightshade the guard then spends, so nothing needs fetching beforehand.
        return List.of();
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    public NightshadePickupTransport withPlacement(String id) {
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
        return "takes a nightshade off its spawn for the enclave guard";
    }
}
