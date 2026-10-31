package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Rows.level;

import java.util.List;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.data.SceneryId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;

/**
 * The Underground Pass old bridge, brought down with a lit arrow, as one crossing from Koftik's floor
 * to the far side, played by a hand-written script. Its fare is {@link #LIT_ARROWS} plain arrows,
 * each made into a lit arrow one at a time: Koftik gives a damp cloth only while none is carried, the
 * cloth wrapped round an arrow makes one wrapped arrow, and the fire lights it. The shot needs a bow,
 * current Ranged 25 and the {@link #LEDGE} tile; it spends the lit arrow and misses one time in five,
 * whatever the level. A hit lowers the bridge for 10 seconds and moves the player across.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/npcs/UndergroundPassKoftik.java#L155">UndergroundPassKoftik.onTalkNpc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L42">UndergroundPassMechanismMap1.onUseInv</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L67">UndergroundPassMechanismMap1.onUseLoc</a>
 */
public final class UndergroundPassOldBridgeTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.UNDERGROUND_PASS_OLD_BRIDGE);

    /** The lit arrows one crossing makes: a shot and two retries. */
    public static final int LIT_ARROWS = 3;

    /**
     * The free inventory slots the arrow chain needs at its peak, from none of it carried: the next
     * cloth beside the stack of lit arrows.
     */
    public static final int PEAK_ROOM = 2;

    public static final int BRIDGE_ID = 726;
    /** The lowered bridge, on the same tile for 10 seconds after a hit. */
    public static final int BRIDGE_DOWN_ID = 727;

    public static final Point BRIDGE = Point.of(704, 3417);
    /** The tile the script shoots from; the shot has effect only from {@code y == 3417} or {@code x >= 701}. */
    public static final Point LEDGE = Point.of(703, 3417);

    public static final Point LANDING = Point.of(709, 3420);
    private static final Point FLOOR = Point.of(702, 3421);

    public static final int FIRE_ID = SceneryId.FIRE.id();
    public static final Point FIRE = Point.of(701, 3420);
    public static final int KOFTIK_ID = NpcId.KOFTIK_CAVE1.id();
    /** The answer to Koftik's menu at quest stage 2; at later stages he gives the cloth without one. */
    public static final String KOFTIK_ANSWER = "not to worry";

    private static final int LIT = ItemId.LIT_ARROW.id();
    private static final int WRAPPED = ItemId.ARROW.id();
    private static final int CLOTH = ItemId.DAMP_CLOTH.id();

    private static final Prereq CROSSES = Prereq.all(
            level(Skill.RANGED, 25),
            Kit.BOW_GATE,
            Prereq.fact(Fact.questAtLeast(QuestId.UNDERGROUND_PASS.id(), 2)),
            Kit.ARROWS_GATE,
            Prereq.fact(Fact.roomFor(CLOTH, PEAK_ROOM)));

    private static final int COST = 560;

    private String name;
    private String placementId;

    /** The crossing as declared by constant {@code id}, or none when the world has no old bridge. */
    public static List<Transport> forWorld(ServerData env, String id) {
        boolean placed = env.sceneryLocs().stream()
                .anyMatch(l -> l.id() == BRIDGE_ID
                        && l.pos().x() == BRIDGE.x()
                        && l.pos().y() == BRIDGE.y());
        if (!placed) {
            return List.of();
        }
        return List.of(new UndergroundPassOldBridgeTransport().withPlacement(id));
    }

    /**
     * The free slots the chain still needs from {@code pack} part-way through it: 0 once three arrows
     * are lit, or once some are lit and a cloth or wrapped arrow is carried; else 1 once any link of
     * the chain is carried; else {@link #PEAK_ROOM}.
     */
    public static int roomWanted(Inventory pack) {
        long lit = pack.count(LIT);
        boolean link = pack.count(WRAPPED) > 0 || pack.count(CLOTH) > 0;
        if (lit >= LIT_ARROWS || (lit > 0 && link)) {
            return 0;
        }
        return lit > 0 || link ? 1 : PEAK_ROOM;
    }

    @Override
    public Prereq prereq() {
        return CROSSES;
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return List.of(new Consumed(arrowsFor(account), LIT_ARROWS));
    }

    /**
     * Whether {@code account} holds a crossing's fare: a chain already under way, or {@link #LIT_ARROWS}
     * arrows of the tier {@link #consumes} charges, carried or banked.
     */
    public static boolean fareOwned(AccountFacts account) {
        Inventory pack = account.inventory();
        if (pack.count(LIT) > 0 || pack.count(WRAPPED) > 0 || pack.count(CLOTH) > 0) {
            return true;
        }
        ItemId a = arrowsFor(account);
        return pack.count(a.id()) + account.bank().count(a.id()) >= LIT_ARROWS;
    }

    // The arrows a wrap spends: the first tier owned a whole fare of, carried tiers first; failing that
    // the first tier carried, else the first banked, else bronze.
    private static ItemId arrowsFor(AccountFacts account) {
        for (boolean carriedOnly : new boolean[] {true, false}) {
            for (ItemId a : Kit.ARROWS) {
                long carried = account.inventory().count(a.id());
                if ((!carriedOnly || carried > 0) && carried + account.bank().count(a.id()) >= LIT_ARROWS) {
                    return a;
                }
            }
        }
        for (ItemId a : Kit.ARROWS) {
            if (account.inventory().count(a.id()) > 0) {
                return a;
            }
        }
        for (ItemId a : Kit.ARROWS) {
            if (account.bank().count(a.id()) > 0) {
                return a;
            }
        }
        return ItemId.BRONZE_ARROWS;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == FLOOR.x() && y == FLOOR.y();
    }

    @Override
    public List<Point> entries() {
        return List.of(FLOOR);
    }

    @Override
    public Point destinationFrom(Point from) {
        return LANDING;
    }

    @Override
    public int cost() {
        return COST;
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    public UndergroundPassOldBridgeTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }
}
