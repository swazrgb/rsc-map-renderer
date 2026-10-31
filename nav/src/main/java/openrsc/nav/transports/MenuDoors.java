package openrsc.nav.transports;

import static openrsc.bot.api.quest.QuestId.UNDERGROUND_PASS;
import static openrsc.bot.api.quest.QuestId.WATCHTOWER;
import static openrsc.nav.transports.spec.EveryPlacement.everyBoundary;
import static openrsc.nav.transports.spec.MenuDoor.menuDoor;
import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.from;
import static openrsc.nav.transports.spec.Rows.notQuestComplete;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.pays;
import static openrsc.nav.transports.spec.Rows.questAtLeast;
import static openrsc.nav.transports.spec.Rows.sequenced;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.when;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.Door;
import openrsc.nav.transports.spec.EveryPlacement;
import openrsc.nav.transports.spec.MenuDoor;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Doors and windows whose click opens a menu, answered from the row, before the player is let
 * through.
 */
public enum MenuDoors implements TransportDef {
    /**
     * The combination is B, R, A, D, asked one dial at a time; "postition" is the server's spelling.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TribalTotem.java#L484">TribalTotem.onOpBound</a>
     */
    TRIBAL_TOTEM_DIAL_DOOR_S_N(menuDoor(
            98,
            561,
            586,
            0,
            from(561, 585),
            to(561, 586),
            90,
            answers("position b"),
            answers("position r"),
            answers("postition a"),
            answers("position d"),
            sequenced())),
    TRIBAL_TOTEM_DIAL_DOOR_N_S(menuDoor(
            98,
            561,
            586,
            0,
            from(561, 586),
            to(561, 585),
            90,
            answers("position b"),
            answers("position r"),
            answers("postition a"),
            answers("position d"),
            sequenced())),

    /**
     * Going in anyway, past the guard's warning, sets a Black Knight within 7 tiles on the player.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/BlackKnightsFortress.java#L342">BlackKnightsFortress.onOpBound</a>
     */
    BLACK_KNIGHT_MEETING_DOOR_IN_W_E(
            menuDoor(39, 275, 439, 1, from(274, 439), to(275, 439), 90, answers("anyway", "going in"))),
    BLACK_KNIGHT_MEETING_DOOR_OUT_E_W(menuDoor(39, 275, 439, 1, from(275, 439), to(274, 439), 10)),

    /**
     * Each try is a coin toss; a miss asks again, and the same answer takes the retry.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L3328">TouristTrap.attemptBendBar</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L3332">TouristTrap.attemptBendBar</a>
     */
    TOURIST_TRAP_JAIL_WINDOW_OUT(menuDoor(
            178,
            90,
            802,
            1,
            from(89, 802),
            to(90, 802),
            120,
            answers("bend the bar"),
            note("a coin toss; a failure offers another try"))),
    TOURIST_TRAP_JAIL_WINDOW_IN(menuDoor(
            178,
            90,
            802,
            1,
            from(90, 802),
            to(89, 802),
            120,
            answers("bend the bar"),
            note("a coin toss; a failure offers another try"))),

    /**
     * The first click only asks for a gift; the guard takes the rock cake on the next, and puts the
     * player on (663,812).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L477">WatchTowerObstacles.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L567">WatchTowerObstacles.cakeCheckGuard</a>
     */
    GU_TANOTH_SW_BATTLEMENT_811_E_W_ROCK_CAKE(menuDoor(
            195,
            665,
            811,
            1,
            from(665, 811),
            to(663, 812),
            120,
            answers("friend"),
            pays(ItemId.ROCK_CAKE.id(), 1),
            when(ogreGiftNotYetGiven()))),
    GU_TANOTH_SW_BATTLEMENT_812_E_W_ROCK_CAKE(menuDoor(
            195,
            665,
            812,
            1,
            from(665, 812),
            to(663, 812),
            120,
            answers("friend"),
            pays(ItemId.ROCK_CAKE.id(), 1),
            when(ogreGiftNotYetGiven()))),

    /**
     * Each side's doorman asks a diamond; the handler finds him by the player's own tile (x 115 or
     * 116, y 3538 or 3539), which only these two doors' flanks are.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L813">DoorAction.onWallObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L842">DoorAction.onWallObjectAction</a>
     */
    LOST_CITY_MARKET_DOOR_A_W_E(menuDoor(
            67, 116, 3537, 1, from(115, 3537), to(116, 3537), 90, answers("okay"), pays(ItemId.DIAMOND.id(), 1))),
    LOST_CITY_MARKET_DOOR_A_E_W(menuDoor(
            67, 116, 3537, 1, from(116, 3537), to(115, 3537), 90, answers("okay"), pays(ItemId.DIAMOND.id(), 1))),
    LOST_CITY_MARKET_DOOR_B_S_N(menuDoor(
            67, 117, 3539, 0, from(117, 3538), to(117, 3539), 90, answers("okay"), pays(ItemId.DIAMOND.id(), 1))),
    LOST_CITY_MARKET_DOOR_B_N_S(menuDoor(
            67, 117, 3539, 0, from(117, 3539), to(117, 3538), 90, answers("okay"), pays(ItemId.DIAMOND.id(), 1))),

    /**
     * The gap moves the player to the railing's z - 1 from its own row, else onto its row, so only a
     * dir-0 railing is a crossing.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassDungeonFloor.java#L138">UndergroundPassDungeonFloor.onOpBound</a>
     */
    UPASS_KALRAG_RAILING(everyBoundary(
            171,
            p -> p.dir() != 0
                    ? List.of()
                    : List.of(
                            menuDoor(
                                    171,
                                    p.x(),
                                    p.z(),
                                    0,
                                    p.at(),
                                    p.plus(0, -1),
                                    90,
                                    answers("lets do it"),
                                    when(kalrag())),
                            menuDoor(
                                    171,
                                    p.x(),
                                    p.z(),
                                    0,
                                    p.plus(0, -1),
                                    p.at(),
                                    90,
                                    answers("lets do it"),
                                    when(kalrag()))))),

    /**
     * From y &lt;= 844 the door puts the player out on (400,845) free; from anywhere else Yohnus asks
     * 20 coins to let them in to (400,844).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/Yohnus.java#L105">Yohnus.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/Yohnus.java#L125">Yohnus.onOpBound</a>
     */
    SHILO_BLACKSMITH_DOOR(everyBoundary(165, MenuDoors::blacksmithsDoor).writtenFor(Point.of(400, 845))),
    ;

    private static List<MenuDoor> blacksmithsDoor(EveryPlacement.Placed p) {
        List<MenuDoor> out = new ArrayList<>(2);
        for (Point from :
                new Point[] {Door.lowFlank(true, p.x(), p.z(), p.dir()), Door.highFlank(true, p.x(), p.z(), p.dir())}) {
            if (from == null) {
                return List.of();
            }
            out.add(
                    from.y() <= 844
                            ? menuDoor(165, p.x(), p.z(), p.dir(), from, Point.of(400, 845), 80)
                            : menuDoor(
                                    165,
                                    p.x(),
                                    p.z(),
                                    p.dir(),
                                    from,
                                    Point.of(400, 844),
                                    90,
                                    answers("use furnace"),
                                    pays(ItemId.COINS.id(), 20)));
        }
        return out;
    }

    private static Prereq kalrag() {
        return Prereq.any(questAtLeast(UNDERGROUND_PASS, 7), cache("doll_of_iban"));
    }

    // A guard that has its gift, or once the quest is done, lets the player over without a cake:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L454
    private static Prereq ogreGiftNotYetGiven() {
        return Prereq.all(Prereq.notFact(Fact.cacheBool("has_ogre_gift", true)), notQuestComplete(WATCHTOWER));
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    MenuDoors(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (MenuDoors d : values()) {
            out.addAll(d.expand(env));
        }
        return out;
    }
}
