package openrsc.nav.transports;

import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.bySide;
import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.canKillAt;
import static openrsc.nav.transports.spec.Rows.clickAt;
import static openrsc.nav.transports.spec.Rows.level;
import static openrsc.nav.transports.spec.Rows.notQuestComplete;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyFrom;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.playerYAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.secondCommand;
import static openrsc.nav.transports.spec.Rows.spawned;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.usesItem;
import static openrsc.nav.transports.spec.Rows.wearing;
import static openrsc.nav.transports.spec.Rows.when;
import static openrsc.nav.transports.spec.Rows.xAtLeast;
import static openrsc.nav.transports.spec.Rows.xAtMost;
import static openrsc.nav.transports.spec.Rows.xIn;
import static openrsc.nav.transports.spec.Rows.zAtLeast;
import static openrsc.nav.transports.spec.Rows.zAtMost;
import static openrsc.nav.transports.spec.Rows.zIn;
import static openrsc.nav.transports.spec.SceneryCrossing.scenery;
import static openrsc.nav.transports.spec.SceneryObstacle.obstacle;
import static openrsc.nav.transports.spec.SceneryObstacle.obstacleAnywhere;
import static openrsc.nav.transports.spec.WorldGate.rule;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.EveryPlacement;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * The quest obstacles that move the player: an object to interact with, or to use an item on, and
 * a teleport. A row whose handler matches the object by id alone is every placement of it; one
 * whose handler tests the object's own tile is declared on that tile.
 */
public enum QuestObstacles implements TransportDef {
    UNDERGROUND_PASS_GRILL_777(obstacleAnywhere(777, to(681, 3446), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_785(obstacleAnywhere(785, to(683, 3446), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_786(obstacleAnywhere(786, to(683, 3448), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_787(obstacleAnywhere(787, to(685, 3448), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_788(obstacleAnywhere(788, to(687, 3448), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_789(obstacleAnywhere(789, to(687, 3450), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_790(obstacleAnywhere(790, to(687, 3452), QuestObstacles.COST)),
    UNDERGROUND_PASS_GRILL_791(obstacleAnywhere(791, to(689, 3452), QuestObstacles.COST)),

    UNDERGROUND_PASS_ROCK_792_WALK_HERE_EAST(obstacleAnywhere(792, to(679, 3447), QuestObstacles.COST)),
    UNDERGROUND_PASS_ROCK_793_WALK_HERE_WEST(obstacleAnywhere(793, to(690, 3452), QuestObstacles.COST)),

    UNDERGROUND_PASS_CAGE_LEVER_801(obstacleAnywhere(801, to(690, 3451), QuestObstacles.COST)),

    /**
     * Open once all four orbs of light are destroyed, or from stage 7.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassWell.java#L25">UndergroundPassWell.onOpLoc</a>
     */
    UNDERGROUND_PASS_WELL_814_DROP_DOWN(obstacleAnywhere(
            814,
            to(722, 3461),
            QuestObstacles.COST,
            when(Prereq.any(
                    Prereq.fact(Fact.questAtLeast(QuestId.UNDERGROUND_PASS.id(), 7)),
                    Prereq.all(
                            Prereq.fact(Fact.cacheBool("orb_of_light1", true)),
                            Prereq.fact(Fact.cacheBool("orb_of_light2", true)),
                            Prereq.fact(Fact.cacheBool("orb_of_light3", true)),
                            Prereq.fact(Fact.cacheBool("orb_of_light4", true))))))),

    UNDERGROUND_PASS_SOUTH_STONE_STEPS_921(obstacleAnywhere(921, to(739, 667), QuestObstacles.COST)),

    UNDERGROUND_PASS_DUG_UP_SOIL_840_ENTER_TUNNEL(
            obstacleAnywhere(840, to(745, 3457), QuestObstacles.COST, answers("yep", "let's do it", "yes"))),
    UNDERGROUND_PASS_DUG_UP_SOIL_839_ENTER_TUNNEL(
            obstacleAnywhere(839, to(747, 3470), QuestObstacles.COST, answers("yep", "let's do it", "yes"))),

    /**
     * The passage swings down under the player: with the rope tied to the stalagmite they lower
     * themselves to the floor, without it they land on spikes.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassOrbs.java#L119">UndergroundPassOrbs.onOpLoc</a>
     */
    UNDERGROUND_PASS_SOUTH_WEST_PASSAGE_815_DROP(obstacleAnywhere(
            815,
            to(709, 3472),
            QuestObstacles.COST,
            when(Prereq.fact(Fact.cacheBool("stalagmite", false))),
            canKillAt(6))),
    UNDERGROUND_PASS_SOUTH_WEST_PASSAGE_815_ROPE_LOWERS(obstacleAnywhere(
            815, to(716, 3481), QuestObstacles.COST, when(Prereq.fact(Fact.cacheBool("stalagmite", true))))),

    /**
     * The landing is picked by the passage's own x, so each placement is declared.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L192">UndergroundPassObstaclesMap2.onOpLoc</a>
     */
    UNDERGROUND_PASS_TRAP_PASSAGE_873_735(obstacle(873, 735, 3489, to(737, 3489), QuestObstacles.COST, canKillAt(6))),
    UNDERGROUND_PASS_TRAP_PASSAGE_873_737(obstacle(873, 737, 3489, to(737, 3489), QuestObstacles.COST, canKillAt(6))),

    /**
     * The plank is spent, and lands by the passage's own x.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap2.java#L54">UndergroundPassMechanismMap2.onUseLoc</a>
     */
    UNDERGROUND_PASS_TRAP_PASSAGE_873_USE_PLANK(
            obstacle(873, 737, 3489, to(732, 3489), QuestObstacles.COST, usesItem(ItemId.PLANK.id(), 1))),

    /**
     * Open once the flames of Zamorak are fed, or from stage 7.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L221">UndergroundPassObstaclesMap2.onOpLoc</a>
     */
    UNDERGROUND_PASS_GATE_OF_IBAN_722(obstacleAnywhere(
            722,
            to(770, 3417),
            QuestObstacles.COST,
            when(Prereq.any(
                    Prereq.fact(Fact.questAtLeast(QuestId.UNDERGROUND_PASS.id(), 7)),
                    Prereq.all(
                            Prereq.fact(Fact.cacheBool("flames_of_zamorak1", true)),
                            Prereq.fact(Fact.cacheBool("flames_of_zamorak2", true)),
                            Prereq.fact(Fact.cacheIntAtLeast("flames_of_zamorak3", 2))))))),

    UNDERGROUND_PASS_NORTH_PASSAGE_825_USE_PLANK(
            obstacleAnywhere(825, to(728, 3435), QuestObstacles.COST, usesItem(ItemId.PLANK.id(), 1))),
    UNDERGROUND_PASS_NORTH_PASSAGE_829_USE_PLANK(
            obstacleAnywhere(829, to(728, 3441), QuestObstacles.COST, usesItem(ItemId.PLANK.id(), 1))),

    /**
     * The side is the player's absolute y: from y 762 on, at stage 8, the worker lets the player in
     * for Glough's password; from anywhere else the gate walks them through.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L1443">GrandTree.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L1460">GrandTree.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L1515">GrandTree.onOpLoc</a>
     */
    GRAND_TREE_SHIPYARD_GATE(everyScenery(
                    624,
                    p -> List.of(
                            obstacle(624, p.x(), p.z(), to(401, 763), QuestObstacles.COST, zAtMost(761)),
                            obstacle(
                                    624,
                                    p.x(),
                                    p.z(),
                                    to(402, 760),
                                    QuestObstacles.COST,
                                    when(Prereq.fact(Fact.questEquals(QuestId.GRAND_TREE.id(), 8))),
                                    answers("glough sent me"),
                                    answers("ka"),
                                    answers("lu"),
                                    answers("min"),
                                    zAtLeast(762))))
            .writtenFor(Point.of(401, 762))),

    /**
     * The handler acts only on the tile at (416, 161).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L104">RandomObjects.onOpLoc</a>
     */
    GNOME_STONE_TILE_TWIST_DOWN(obstacle(
            643,
            416,
            161,
            to(703, 3284),
            QuestObstacles.COST,
            when(Prereq.fact(Fact.questComplete(QuestId.GRAND_TREE.id()))))),

    /**
     * Praying at the altar on y 3573 drops the player through a trapdoor.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/prayer/Prayer.java#L37">Prayer.onOpLoc</a>
     */
    YANILLE_CHAOS_ALTAR_TRAPDOOR_625(obstacle(625, 609, 3573, to(608, 3525), QuestObstacles.COST)),

    HAZEEL_CULT_SEWER_RAFT_BACK(obstacleAnywhere(433, to(620, 3478), QuestObstacles.COST)),

    LEGENDS_QUEST_CAVE_BOOKCASE_931_CLIMB_HOLE(obstacleAnywhere(
            931,
            to(444, 3699),
            QuestObstacles.COST,
            answers("yes, i'll climb through the hole", "yes", "climb through"))),

    LEGENDS_QUEST_CRAWL_ROCK_1151(obstacleAnywhere(
            1151,
            to(461, 3700),
            QuestObstacles.COST,
            when(Prereq.all(Prereq.fact(Fact.questAtLeast(QuestId.LEGENDS_QUEST.id(), 2)), level(Skill.AGILITY, 50))),
            answers("yes, i'll crawl through", "yes", "crawl through"),
            canKillAt(5))),

    WATCHTOWER_DARK_CAVE_EXIT_ROCK_1008(obstacleAnywhere(1008, to(638, 776), QuestObstacles.COST)),

    /**
     * A search with Ana in a barrel is refused for lack of space. The landing is picked by the
     * cart's own tile, so each cart is declared.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/Tourist_Trap_Mechanism.java#L420">Tourist_Trap_Mechanism.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/Tourist_Trap_Mechanism.java#L430">Tourist_Trap_Mechanism.onOpLoc</a>
     */
    TOURIST_TRAP_MINE_CART_976_56_3631(obstacle(
            976,
            56,
            3631,
            to(62, 3640),
            QuestObstacles.COST,
            when(Prereq.fact(Fact.notCarrying(ItemId.ANA_IN_A_BARREL.id()))),
            answers("yes, of course", "yes"),
            secondCommand(),
            canKillAt(2))),
    TOURIST_TRAP_MINE_CART_976_62_3639(obstacle(
            976,
            62,
            3639,
            to(55, 3632),
            QuestObstacles.COST,
            when(Prereq.fact(Fact.notCarrying(ItemId.ANA_IN_A_BARREL.id()))),
            answers("yes, of course", "yes"),
            secondCommand(),
            canKillAt(2))),

    TOURIST_TRAP_MINE_CART_ABOVE_1025(
            obstacleAnywhere(1025, to(86, 808), QuestObstacles.COST, answers("yes, i'll get on", "yes"))),

    /**
     * Needs the compound poured on the brick, which lighting it uses up.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/digsite/DigsiteObjects.java#L303">DigsiteObjects.onUseLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/digsite/DigsiteObjects.java#L312">DigsiteObjects.onUseLoc</a>
     */
    DIGSITE_EXPLOSIVE_BRICK_1096(obstacleAnywhere(
            1096,
            to(22, 3379),
            QuestObstacles.COST,
            usesItem(ItemId.TINDERBOX.id(), 0),
            when(Prereq.fact(Fact.cacheBool("brick_ignite", true))))),

    /**
     * Only at stage 5 exactly, on the evil side.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/HazeelCult.java#L1363">HazeelCult.onOpLoc</a>
     */
    HAZEEL_CULT_BOOKCASE_436(obstacleAnywhere(
            436,
            to(614, 2504),
            QuestObstacles.COST,
            when(Prereq.all(
                    Prereq.fact(Fact.questEquals(QuestId.THE_HAZEEL_CULT.id(), 5)),
                    Prereq.fact(Fact.cacheBool("evil_side", true)))))),

    PLAGUE_CITY_DUG_UP_SOIL_447(obstacleAnywhere(
            447,
            to(621, 3414),
            QuestObstacles.COST,
            usesItem(ItemId.SPADE.id(), 0),
            when(Prereq.fact(Fact.questAtLeast(QuestId.PLAGUE_CITY.id(), 3))))),

    PLAGUE_CITY_MUD_PILE_CLIMB_SEWER_EXIT(obstacleAnywhere(448, to(620, 578), QuestObstacles.COST)),

    /**
     * Free once Plague City and Biohazard are both done; before that, from stage 5, only with the
     * gasmask worn.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/PlagueCity.java#L905">PlagueCity.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/PlagueCity.java#L910">PlagueCity.onOpLoc</a>
     */
    PLAGUE_CITY_SEWER_PIPE_TO_WEST_ARDOUGNE_FREE(obstacleAnywhere(
            449,
            to(632, 589),
            QuestObstacles.COST,
            when(Prereq.all(
                    Prereq.fact(Fact.questComplete(QuestId.PLAGUE_CITY.id())),
                    Prereq.fact(Fact.questComplete(QuestId.BIOHAZARD.id())))))),
    PLAGUE_CITY_SEWER_PIPE_TO_WEST_ARDOUGNE_GASMASK(obstacleAnywhere(
            449,
            to(632, 589),
            QuestObstacles.COST,
            when(Prereq.all(
                    Prereq.fact(Fact.questAtLeast(QuestId.PLAGUE_CITY.id(), 5)),
                    Prereq.notFact(Fact.questComplete(QuestId.BIOHAZARD.id())),
                    Prereq.fact(Fact.wearing(ItemId.GASMASK.id())))))),

    SHILO_VILLAGE_WELL_ROCKS_688_CRAWL_IN(obstacleAnywhere(
            688,
            to(471, 3658),
            QuestObstacles.COST,
            when(Prereq.fact(Fact.questAtLeast(QuestId.SHILO_VILLAGE.id(), 5))),
            answers("yes please", "yes", "nothing nicer"),
            secondCommand(),
            canKillAt(3))),

    /**
     * The landing is picked by the rubble's own tile, and no other pile moves the player.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L549">ShiloVillageObjects.onOpLoc</a>
     */
    SHILO_VILLAGE_RUBBLE_670_348_3708(obstacle(
            670,
            348,
            3708,
            to(356, 3667),
            QuestObstacles.COST,
            answers("yes, i'll wriggle through", "yes", "wriggle"))),
    SHILO_VILLAGE_RUBBLE_670_357_3668(obstacle(
            670,
            357,
            3668,
            to(347, 3709),
            QuestObstacles.COST,
            answers("yes, i'll wriggle through", "yes", "wriggle"))),

    SHILO_WATERFALL_CLIMB_OUT_696(
            obstacleAnywhere(696, to(339, 808), QuestObstacles.COST, answers("climb out", "yes"), canKillAt(5))),

    /**
     * Opened by the server's shared gate routine, which steps the player through by the gate's
     * direction.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/mage_arena/MageArena.java#L661">MageArena.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1819">Functions.doGate</a>
     */
    MAGE_ARENA_GATE_1019(everyScenery(
                    1019,
                    p -> List.of(
                            obstacle(
                                    1019,
                                    p.x(),
                                    p.z(),
                                    p.plus(-1, 0),
                                    QuestObstacles.COST,
                                    xIn(p.x(), p.x()),
                                    zIn(p.z(), p.z() + 1)),
                            obstacle(
                                    1019,
                                    p.x(),
                                    p.z(),
                                    p.at(),
                                    QuestObstacles.COST,
                                    xIn(p.x() - 1, p.x() - 1),
                                    zIn(p.z(), p.z() + 1))))
            .facing(0)),
    MAGE_ARENA_GATE_1020(everyScenery(
                    1020,
                    p -> List.of(
                            obstacle(
                                    1020,
                                    p.x(),
                                    p.z(),
                                    p.plus(1, 0),
                                    QuestObstacles.COST,
                                    xIn(p.x(), p.x()),
                                    zIn(p.z(), p.z() + 1)),
                            obstacle(
                                    1020,
                                    p.x(),
                                    p.z(),
                                    p.at(),
                                    QuestObstacles.COST,
                                    xIn(p.x() + 1, p.x() + 1),
                                    zIn(p.z(), p.z() + 1))))
            .facing(4)),

    /**
     * The side is the player's absolute y, and the landing a fixed tile per boulder and side.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L425">LegendsQuestGameObjects.onOpLoc</a>
     */
    LEGENDS_BOULDER_1117_SMASH(
            everyScenery(1117, p -> boulder(p, p.plus(0, 2), 3704, to(441, 3707), 3707, to(442, 3704)))),
    LEGENDS_BOULDER_1184_SMASH(
            everyScenery(1184, p -> boulder(p, p.plus(1, 2), 3708, to(441, 3711), 3711, to(441, 3708)))),
    LEGENDS_BOULDER_1185_SMASH(
            everyScenery(1185, p -> boulder(p, p.plus(1, 2), 3712, to(441, 3715), 3715, to(441, 3712)))),

    /**
     * The side is the player's absolute x: from x 792 or less a player in Zamorak robes goes in, from
     * anywhere else out.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap3.java#L124">UndergroundPassObstaclesMap3.onOpLoc</a>
     */
    UPASS_ZAMORAKIAN_TEMPLE_DOOR_869(everyScenery(
            869,
            p -> List.of(
                    obstacle(869, p.x(), p.z(), to(791, 3469), QuestObstacles.COST, xAtLeast(793)),
                    obstacle(
                            869,
                            p.x(),
                            p.z(),
                            to(795, 3469),
                            QuestObstacles.COST,
                            when(Prereq.all(
                                    Prereq.fact(Fact.wearing(ItemId.ROBE_OF_ZAMORAK_TOP.id())),
                                    Prereq.fact(Fact.wearing(ItemId.ROBE_OF_ZAMORAK_BOTTOM.id())),
                                    Prereq.notFact(Fact.questComplete(QuestId.UNDERGROUND_PASS.id())))),
                            xAtMost(792),
                            canKillAt(5))))),

    WATERFALL_DUNGEON_DOORWAY_486(obstacleAnywhere(486, to(667, 3279), QuestObstacles.COST)),

    /**
     * Nothing happens while the player carries a weapon or armour the tomb bans.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L730">Waterfall_Quest.onUseLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L970">Waterfall_Quest.CANT_GO</a>
     */
    GLARIALS_TOMB_PEBBLE_SLAB_479(obstacleAnywhere(
            479,
            to(631, 3305),
            QuestObstacles.COST,
            usesItem(ItemId.GLARIALS_PEBBLE.id(), 0),
            when(Prereq.fact(Fact.notCarryingTombBanned())))),

    BAXTORIAN_STATUE_483_AMULET(
            obstacleAnywhere(483, to(647, 3267), QuestObstacles.COST, usesItem(ItemId.GLARIALS_AMULET.id(), 0))),

    /**
     * Every placement but the door at (81, 3633), which the handler matches by its tile to let the
     * player out of the mine instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L2760">TouristTrap.onOpLoc</a>
     */
    TOURIST_TRAP_MINE_DOORS_958_ENTER_MINE(everyScenery(
                    958,
                    p -> List.of(obstacle(
                            958,
                            p.x(),
                            p.z(),
                            to(82, 3630),
                            QuestObstacles.COST,
                            when(Prereq.all(
                                    Prereq.fact(Fact.wearing(ItemId.SLAVES_ROBE_TOP.id())),
                                    Prereq.fact(Fact.wearing(ItemId.SLAVES_ROBE_BOTTOM.id())))),
                            zAtMost(p.z()))))
            .except(Point.of(81, 3633))),
    TOURIST_TRAP_MINE_DOORS_958_EXIT_MINE(obstacle(
            958,
            81,
            3633,
            to(82, 802),
            QuestObstacles.COST,
            when(Prereq.all(
                    Prereq.fact(Fact.wearing(ItemId.SLAVES_ROBE_TOP.id())),
                    Prereq.fact(Fact.wearing(ItemId.SLAVES_ROBE_BOTTOM.id())))),
            xAtLeast(81))),

    FISHING_TRAWLER_EXIT_BARREL(everyScenery(
            1070,
            p -> List.of(obstacle(
                    1070, p.x(), p.z(), to(550, 711), QuestObstacles.COST, onlyFrom(p.x(), p.z()), canKillAt(3))))),

    /**
     * No world places this doorway: the plugin spawns it when the trees beside it are pulled apart,
     * which reveals nothing once the quest is done unless post-quest regions stay open.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L216">ShiloVillageObjects.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L207">ShiloVillageObjects.onOpLoc</a>
     */
    SHILO_HILLSIDE_ENTRANCE(obstacle(
            572,
            350,
            782,
            to(348, 3611),
            QuestObstacles.COST,
            usesItem(ItemId.BONE_KEY.id(), 0),
            spawned(2),
            when(notQuestComplete(QuestId.SHILO_VILLAGE)))),
    /**
     * Declared on its tile: the extra locs place a twin of this gate at (393, 172), which a
     * derived row would give these tomb landings too.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/conf/server/defs/locs/SceneryLocsOther.json#L3">SceneryLocsOther</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L127">ShiloVillageObjects.onOpLoc</a>
     */
    SHILO_METALLIC_GATE_EXIT(obstacle(577, 348, 3615, to(348, 3614), QuestObstacles.COST, zAtLeast(3616))),
    SHILO_METALLIC_GATE_ENTER(obstacle(
            577,
            348,
            3615,
            to(348, 3616),
            QuestObstacles.COST,
            when(wearing(ItemId.BEADS_OF_THE_DEAD.id())),
            answers("fearless"),
            zAtMost(3615))),

    /**
     * The landing is read off the rock's own y: the row at y 3619 climbs up, every other one down.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L104">ShiloVillageObjects.onOpLoc</a>
     */
    SHILO_CAVE_ROCKS(everyScenery(
            719,
            p -> List.of(obstacle(
                    719,
                    p.x(),
                    p.z(),
                    p.z() == 3619 ? to(348, 3616) : to(348, 3620),
                    QuestObstacles.COST,
                    when(Prereq.all(wearing(ItemId.BEADS_OF_THE_DEAD.id()), level(Skill.AGILITY, 32))),
                    canKillAt(3))))),
    /**
     * The side is the player's absolute y. The doors open only once their key is in the cache, which
     * opening the tomb stores and leaving by the exit door removes.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L61">ShiloVillageObjects.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L640">ShiloVillageObjects.onUseLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L657">ShiloVillageObjects.onUseLoc</a>
     */
    SHILO_TOMB_DOORS(everyScenery(
            794,
            p -> List.of(
                    obstacle(
                            794,
                            p.x(),
                            p.z(),
                            to(377, 3631),
                            QuestObstacles.COST,
                            when(cache("tomb_door_shilo")),
                            zAtLeast(3632)),
                    obstacle(
                            794,
                            p.x(),
                            p.z(),
                            to(377, 3633),
                            QuestObstacles.COST,
                            when(cache("tomb_door_shilo")),
                            zAtMost(3631))))),
    /**
     * Without the beads of the dead worn the player first loses half their current Hits, which never
     * kills.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/shilovillage/ShiloVillageObjects.java#L648">ShiloVillageObjects.onUseLoc</a>
     */
    SHILO_EXIT_DOOR(obstacleAnywhere(
            583,
            to(350, 782),
            QuestObstacles.COST,
            usesItem(ItemId.BONE_KEY.id(), 0),
            note("without the beads of the dead worn, invisible hands take half your hits"))),

    /**
     * After the quest the temple is in ruins, unless post-quest regions stay open.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap3.java#L126">UndergroundPassObstaclesMap3.onOpLoc</a>
     */
    UPASS_ZAMORAKIAN_TEMPLE_DOOR_869_ENTER_AFTER_QUEST(onlyIn(
            rule(ServerRules::postQuestRegionsOpen),
            everyScenery(
                    869,
                    p -> List.of(obstacle(
                            869,
                            p.x(),
                            p.z(),
                            to(795, 3469),
                            QuestObstacles.COST,
                            when(Prereq.all(
                                    Prereq.fact(Fact.wearing(ItemId.ROBE_OF_ZAMORAK_TOP.id())),
                                    Prereq.fact(Fact.wearing(ItemId.ROBE_OF_ZAMORAK_BOTTOM.id())),
                                    questComplete(QuestId.UNDERGROUND_PASS))),
                            xAtMost(792),
                            canKillAt(5)))))),
    ;

    /**
     * One boulder: from y {@code northAtMost} or less to {@code north}, and from y {@code southAtLeast}
     * on, standing on {@code southFrom} below the 2 by 2 boulder, to {@code south}. The server moves
     * nobody from the rows between.
     */
    private static List<TransportSpec> boulder(
            EveryPlacement.Placed p, Point southFrom, int northAtMost, Point north, int southAtLeast, Point south) {
        Prereq gate = Prereq.all(level(Skill.MINING, 52), Kit.PICKAXE);
        List<TransportSpec> out = new ArrayList<>();
        out.add(obstacle(p.objectId(), p.x(), p.z(), north, QuestObstacles.COST, when(gate), zAtMost(northAtMost)));
        out.addAll(bySide(
                playerYAtLeast(southAtLeast),
                (from, fromSouth) -> fromSouth
                        ? scenery(p.objectId(), from, south, QuestObstacles.COST, clickAt(p.at()), when(gate))
                        : null,
                southFrom));
        return out;
    }

    private static final int COST = 30;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    QuestObstacles(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (QuestObstacles o : values()) {
            out.addAll(o.expand(env));
        }
        return out;
    }
}
