package openrsc.nav.transports;

import static openrsc.bot.api.quest.QuestId.LEGENDS_QUEST;
import static openrsc.bot.api.quest.QuestId.TOURIST_TRAP;
import static openrsc.bot.api.quest.QuestId.UNDERGROUND_PASS;
import static openrsc.bot.api.quest.QuestId.WATCHTOWER;
import static openrsc.nav.transports.spec.AnyPlacement.anyPlacement;
import static openrsc.nav.transports.spec.Bespoke.bespoke;
import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.cacheAtLeast;
import static openrsc.nav.transports.spec.Rows.canKillAt;
import static openrsc.nav.transports.spec.Rows.carrying;
import static openrsc.nav.transports.spec.Rows.clickAt;
import static openrsc.nav.transports.spec.Rows.from;
import static openrsc.nav.transports.spec.Rows.level;
import static openrsc.nav.transports.spec.Rows.notQuestAt;
import static openrsc.nav.transports.spec.Rows.notQuestAtLeast;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyFrom;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.pays;
import static openrsc.nav.transports.spec.Rows.questAt;
import static openrsc.nav.transports.spec.Rows.questAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.secondCommand;
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
import static openrsc.nav.transports.spec.WorldGate.locationData;
import static openrsc.nav.transports.spec.WorldGate.rule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.EveryPlacement.Placed;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Agility obstacles and shortcuts, and the quest-dungeon climbs, jumps and squeezes that work like
 * one: one constant each.
 *
 * <p>A row lands where the server's handler puts the player on success. Where a roll fails the player
 * ends up elsewhere, which no row models; a failure that can kill is folded into the gate. Level gates
 * read the current level, as the handlers do. On an obstacle with no hard minimum, the gate is the
 * roll's floor: the legacy production roll always fails below its required level.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L91">AgilityShortcuts.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L352">Formulae.calcProductionSuccessfulLegacy</a>
 */
public enum AgilityObstacles implements TransportDef {
    // Fixed landings per id:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L179
    FALADOR_SE_WALL_HANDHOLD(anyPlacement(693, to(338, 555), AgilityObstacles.COST, when(level(Skill.AGILITY, 5)))),
    BRIMHAVEN_VINE_SWING_E_W(anyPlacement(694, to(511, 669), AgilityObstacles.COST, when(level(Skill.AGILITY, 10)))),
    BRIMHAVEN_VINE_SWING_W_E(anyPlacement(695, to(508, 668), AgilityObstacles.COST, when(level(Skill.AGILITY, 10)))),
    EDGEVILLE_DUNGEON_ROPE_N_S(anyPlacement(684, to(207, 3221), AgilityObstacles.COST, when(level(Skill.AGILITY, 15)))),
    EDGEVILLE_DUNGEON_ROPE_S_N(anyPlacement(685, to(206, 3225), AgilityObstacles.COST, when(level(Skill.AGILITY, 15)))),
    COAL_TRUCKS_LOG_BALANCE_E_W(anyPlacement(681, to(592, 458), AgilityObstacles.COST, when(level(Skill.AGILITY, 20)))),
    COAL_TRUCKS_LOG_BALANCE_W_E(anyPlacement(680, to(598, 458), AgilityObstacles.COST, when(level(Skill.AGILITY, 20)))),
    YANILLE_AGILITY_DUNGEON_ROPE_N_S(
            anyPlacement(628, to(596, 3581), AgilityObstacles.COST, when(level(Skill.AGILITY, 57)))),
    YANILLE_AGILITY_DUNGEON_ROPE_S_N(
            anyPlacement(627, to(598, 3585), AgilityObstacles.COST, when(level(Skill.AGILITY, 57)))),
    YANILLE_AGILITY_LEDGE_N_S(anyPlacement(614, to(601, 3563), AgilityObstacles.COST, when(level(Skill.AGILITY, 40)))),
    YANILLE_AGILITY_LEDGE_S_N(anyPlacement(615, to(601, 3557), AgilityObstacles.COST, when(level(Skill.AGILITY, 40)))),
    YANILLE_RUBBLE_DOWN(anyPlacement(636, to(580, 3525), AgilityObstacles.COST, when(level(Skill.AGILITY, 67)))),
    YANILLE_RUBBLE_UP(anyPlacement(633, to(582, 3573), AgilityObstacles.COST, when(level(Skill.AGILITY, 67)))),
    YANILLE_PIPE_W_E(anyPlacement(656, to(608, 3568), AgilityObstacles.COST, when(level(Skill.AGILITY, 49)))),
    YANILLE_PIPE_E_W(anyPlacement(657, to(605, 3568), AgilityObstacles.COST, when(level(Skill.AGILITY, 49)))),

    /**
     * The swing back from Grew Island; unlike the tied rope out, it checks no level.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L426">AgilityShortcuts.onOpLoc</a>
     */
    GREW_ISLAND_ROPE_S_N(anyPlacement(664, to(666, 755), AgilityObstacles.COST)),
    YANILLE_CLIMBING_ROCKS(anyPlacement(1029, to(624, 741), AgilityObstacles.COST, when(level(Skill.AGILITY, 15)))),
    YANILLE_WATCHTOWER_HANDHOLDS(
            anyPlacement(658, to(637, 1680), AgilityObstacles.COST, when(level(Skill.AGILITY, 18)))),

    WILDERNESS_AGILITY_COURSE_VINE_CLIMB_CLIFF(anyPlacement(709, to(304, 120), AgilityObstacles.COST)),

    UNDERGROUND_PASS_LEDGE_862(
            anyPlacement(862, to(730, 3494), AgilityObstacles.COST, when(level(Skill.AGILITY, 1)), canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_863(
            anyPlacement(863, to(763, 3442), AgilityObstacles.COST, when(level(Skill.AGILITY, 1)), canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_865(
            anyPlacement(865, to(728, 3499), AgilityObstacles.COST, when(level(Skill.AGILITY, 1)), canKillAt(2))),

    // These three land further east from quest stage 4 on, a completed quest included.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassAgilityObstacles.java#L42
    UNDERGROUND_PASS_LEDGE_864_BEFORE_STAGE_4(anyPlacement(
            864,
            to(734, 3496),
            AgilityObstacles.COST,
            when(Prereq.all(level(Skill.AGILITY, 1), notQuestAtLeast(UNDERGROUND_PASS, 4))),
            canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_864_FROM_STAGE_4(anyPlacement(
            864,
            to(751, 3496),
            AgilityObstacles.COST,
            when(Prereq.all(level(Skill.AGILITY, 1), questAtLeast(UNDERGROUND_PASS, 4))),
            canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_872_BEFORE_STAGE_4(anyPlacement(
            872,
            to(748, 3497),
            AgilityObstacles.COST,
            when(Prereq.all(level(Skill.AGILITY, 1), notQuestAtLeast(UNDERGROUND_PASS, 4))),
            canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_872_FROM_STAGE_4(anyPlacement(
            872,
            to(765, 3497),
            AgilityObstacles.COST,
            when(Prereq.all(level(Skill.AGILITY, 1), questAtLeast(UNDERGROUND_PASS, 4))),
            canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_866_BEFORE_STAGE_4(anyPlacement(
            866,
            to(738, 3501),
            AgilityObstacles.COST,
            when(Prereq.all(level(Skill.AGILITY, 1), notQuestAtLeast(UNDERGROUND_PASS, 4))),
            canKillAt(2))),
    UNDERGROUND_PASS_LEDGE_866_FROM_STAGE_4(anyPlacement(
            866,
            to(755, 3501),
            AgilityObstacles.COST,
            when(Prereq.all(level(Skill.AGILITY, 1), questAtLeast(UNDERGROUND_PASS, 4))),
            canKillAt(2))),

    UNDERGROUND_PASS_WALL_GRILL_WEST_CLIMB_ACROSS(anyPlacement(838, to(766, 3463), AgilityObstacles.COST)),

    UNDERGROUND_PASS_WALL_GRILL_EAST_CLIMB_ACROSS(bespoke(UndergroundPassEastGrillTransport::forWorld)),

    // 3 in 10 climbs fall back onto the spikes.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassOrbs.java#L132
    UNDERGROUND_PASS_GRILL_CLIMB_816(anyPlacement(816, to(737, 3453), AgilityObstacles.COST, canKillAt(6))),

    // The side is the player's absolute x.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L449
    EAST_KARAMJA_LOG_BALANCE(everyScenery(
            692,
            p -> List.of(
                    obstacle(
                            692,
                            p.x(),
                            p.z(),
                            to(370, 781),
                            AgilityObstacles.COST,
                            xAtMost(367),
                            when(level(Skill.AGILITY, 32)),
                            canKillAt(1)),
                    obstacle(
                            692,
                            p.x(),
                            p.z(),
                            to(366, 781),
                            AgilityObstacles.COST,
                            xAtLeast(368),
                            when(level(Skill.AGILITY, 32)),
                            canKillAt(1))))),

    // The side is the player's absolute y.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L473
    EAST_KARAMJA_STEPPING_STONES(everyScenery(
            701,
            p -> List.of(
                    obstacle(
                            701,
                            p.x(),
                            p.z(),
                            to(346, 808),
                            AgilityObstacles.COST,
                            zAtMost(805),
                            when(level(Skill.AGILITY, 32)),
                            canKillAt(2)),
                    obstacle(
                            701,
                            p.x(),
                            p.z(),
                            to(347, 805),
                            AgilityObstacles.COST,
                            zAtLeast(806),
                            when(level(Skill.AGILITY, 32)),
                            canKillAt(2))))),

    // The way is the rock's own x, not the player's.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L160
    SHILO_ROCKS_CLIMB(everyScenery(
            710,
            p -> List.of(obstacle(
                    710,
                    p.x(),
                    p.z(),
                    p.x() == 450 ? to(452, 829) : to(449, 828),
                    AgilityObstacles.COST,
                    when(level(Skill.AGILITY, 32)),
                    answers("easily climb"))))),

    // The side is the player's absolute x.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L116
    SHILO_BRIDGE_FENCE_JUMP(everyScenery(
            691,
            p -> List.of(
                    obstacle(
                            691,
                            p.x(),
                            p.z(),
                            to(460, 828),
                            AgilityObstacles.COST,
                            xAtMost(459),
                            when(level(Skill.AGILITY, 32)),
                            answers("totally brave")),
                    obstacle(
                            691,
                            p.x(),
                            p.z(),
                            to(458, 828),
                            AgilityObstacles.COST,
                            xAtLeast(460),
                            when(level(Skill.AGILITY, 32)),
                            answers("totally brave"))))),

    UNDERGROUND_PASS_LEDGE_732(everyScenery(732, AgilityObstacles::ledgeArms)),
    UNDERGROUND_PASS_LEDGE_750(everyScenery(750, AgilityObstacles::ledgeArms)),
    UNDERGROUND_PASS_LEDGE_751(everyScenery(751, AgilityObstacles::ledgeArms)),
    UNDERGROUND_PASS_LEDGE_752(everyScenery(752, AgilityObstacles::ledgeArms)),
    UNDERGROUND_PASS_LEDGE_753(everyScenery(753, AgilityObstacles::ledgeArms)),

    UNDERGROUND_PASS_ROCK_731(everyScenery(731, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_737(everyScenery(737, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_738(everyScenery(738, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_739(everyScenery(739, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_740(everyScenery(740, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_741(everyScenery(741, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_742(everyScenery(742, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_743(everyScenery(743, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_744(everyScenery(744, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_745(everyScenery(745, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_746(everyScenery(746, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_747(everyScenery(747, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_748(everyScenery(748, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_749(everyScenery(749, p -> rockArms(p, 1))),

    /**
     * Any swamp tile, clicked, drags the player down into the crevasse. Not (697,3441): the first rocks
     * thrown at the crevasse are spawned on that tile, which deletes its swamp until the server
     * restarts.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap1.java#L158">UndergroundPassObstaclesMap1.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L123">UndergroundPassMechanismMap1.onUseLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L494">World.registerGameObject</a>
     */
    UNDERGROUND_PASS_SWAMP_754_DRAG_INTO_CREVASSE(anyPlacement(754, to(690, 3461), AgilityObstacles.COST, canKillAt(2))
            .except(Point.of(697, 3441))),

    /**
     * Rocks thrown on the blessed spider's swamp; the side is the player's absolute y. Not (714,3418):
     * every throw spawns the rocks there, deleting that swamp tile for good.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L141">UndergroundPassMechanismMap1.onUseLoc</a>
     */
    UNDERGROUND_PASS_BLESSED_SPIDER_SWAMP_795_USE_ROCKS(everyScenery(
                    795,
                    p -> List.of(
                            obstacle(
                                    795,
                                    p.x(),
                                    p.z(),
                                    to(715, 3416),
                                    AgilityObstacles.COST,
                                    zAtLeast(3418),
                                    usesItem(ItemId.ROCKS.id(), 1)),
                            obstacle(
                                    795,
                                    p.x(),
                                    p.z(),
                                    to(713, 3420),
                                    AgilityObstacles.COST,
                                    zAtMost(3417),
                                    usesItem(ItemId.ROCKS.id(), 1))))
            .except(Point.of(714, 3418))),

    /**
     * Rocks thrown on the crevasse, from either bank. Stated rather than derived from every placement:
     * the handler matches 754 by id alone, and 754 is also the main floor's swamp, every tile of which
     * would become a crossing of the crevasse.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L125">UndergroundPassMechanismMap1.onUseLoc</a>
     */
    UNDERGROUND_PASS_SWAMP_CROSS_754_W_E_USE_ROCKS(scenery(
            754,
            from(695, 3441),
            to(700, 3441),
            AgilityObstacles.COST,
            usesItem(ItemId.ROCKS.id(), 1),
            clickAt(696, 3441))),
    UNDERGROUND_PASS_SWAMP_CROSS_754_E_W_USE_ROCKS(scenery(
            754,
            from(700, 3441),
            to(695, 3441),
            AgilityObstacles.COST,
            usesItem(ItemId.ROCKS.id(), 1),
            clickAt(699, 3441))),

    // The rope is lassoed and kept.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L108
    UNDERGROUND_PASS_STALACTITE_771_ROPE(
            obstacleAnywhere(771, to(695, 3435), AgilityObstacles.COST, usesItem(ItemId.ROPE.id(), 0))),
    UNDERGROUND_PASS_STALACTITE_798_ROPE(
            obstacleAnywhere(798, to(677, 3435), AgilityObstacles.COST, usesItem(ItemId.ROPE.id(), 0))),
    UNDERGROUND_PASS_STALACTITE_799_ROPE(
            obstacleAnywhere(799, to(682, 3436), AgilityObstacles.COST, usesItem(ItemId.ROPE.id(), 0))),

    UNDERGROUND_PASS_GATE_OF_ZAMORAK(obstacleAnywhere(875, to(763, 3417), AgilityObstacles.COST)),

    SHILO_SMASHED_TABLE_697_CRAFT_RAFT(
            obstacleAnywhere(697, to(341, 810), AgilityObstacles.COST, answers("crude raft"), secondCommand())),

    UNDERGROUND_PASS_OLD_BRIDGE_726_LIT_ARROW(bespoke(UndergroundPassOldBridgeTransport::forWorld)),

    SHILO_TOMB_DOLMEN_724_LOOK(everyScenery(
            724, p -> List.of(scenery(724, p.plus(-1, 1), to(380, 3625), AgilityObstacles.COST, clickAt(p.at()))))),

    UNDERGROUND_PASS_ROCK_756(everyScenery(756, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_757(everyScenery(757, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_758(everyScenery(758, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_759(everyScenery(759, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_760(everyScenery(760, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_762(everyScenery(762, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_766(everyScenery(766, p -> rockArms(p, 1))),
    UNDERGROUND_PASS_ROCK_763(everyScenery(763, p -> rockFrom(p, 1, new int[] {1, 0}, new int[] {-1, 0}))),
    UNDERGROUND_PASS_ROCK_764(everyScenery(764, p -> rockFrom(p, 1, new int[] {1, 0}))),
    UNDERGROUND_PASS_ROCK_765(everyScenery(765, p -> rockFrom(p, 1, new int[] {1, 0}, new int[] {-1, 0}))),

    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_819_CLEAR(everyScenery(819, p -> List.of(westPassageClear(p)))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_819_STEP_OVER(everyScenery(819, p -> westPassageStepOver(p, 2))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_820_CLEAR(everyScenery(820, p -> List.of(westPassageClear(p)))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_820_STEP_OVER(everyScenery(820, p -> westPassageStepOver(p, 4))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_821_CLEAR(everyScenery(821, p -> List.of(westPassageClear(p)))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_821_STEP_OVER(everyScenery(821, p -> westPassageStepOver(p, 6))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_822_CLEAR(everyScenery(822, p -> List.of(westPassageClear(p)))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_822_STEP_OVER(everyScenery(822, p -> westPassageStepOver(p, 8))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_823_CLEAR(everyScenery(823, p -> List.of(westPassageClear(p)))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_823_STEP_OVER(everyScenery(823, p -> westPassageStepOver(p, 10))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_824_CLEAR(everyScenery(824, p -> List.of(westPassageClear(p)))),
    UNDERGROUND_PASS_WEST_PASSAGE_ROCK_824_STEP_OVER(everyScenery(824, p -> westPassageStepOver(p, 12))),

    /**
     * The finished doll thrown into the pit. The server also wants Iban within 10 tiles, which the gate
     * cannot see.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/npcs/UndergroundPassIban.java#L36">UndergroundPassIban.onUseLoc</a>
     */
    UNDERGROUND_PASS_PIT_OF_THE_DAMNED_913_DOLL_OF_IBAN(obstacleAnywhere(
            913,
            to(687, 3485),
            AgilityObstacles.COST,
            usesItem(ItemId.A_DOLL_OF_IBAN.id(), 1),
            when(Prereq.all(
                    notQuestAtLeast(UNDERGROUND_PASS, 8),
                    cache("poison_on_doll"),
                    cache("cons_on_doll"),
                    cache("ash_on_doll"),
                    cache("shadow_on_doll"))))),

    // Both gates turn back 2 in 5 attempts, whatever the level.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/WildernessAgilityCourse.java#L46
    WILDERNESS_AGILITY_RIDGE_GATE_703(
            obstacleAnywhere(703, to(298, 125), AgilityObstacles.COST, when(level(Skill.AGILITY, 52)))),

    WILDERNESS_AGILITY_RIDGE_GATE_704_EXIT(obstacleAnywhere(704, to(298, 134), AgilityObstacles.COST)),

    BARBARIAN_AGILITY_ROPESWING_675_SWING_ACROSS(anyPlacement(
            675,
            to(486, 559),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 35)),
            note("a swing roll, always failed under 35; a slip drops you in the spike pit"))),

    BARBARIAN_AGILITY_LOG_676_BALANCE_ACROSS(anyPlacement(
            676,
            to(492, 563),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 35)),
            note("a balance roll, always failed under 35; a slip lands you in the water"))),

    /**
     * The log again from where its handler stands the player between steps.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/BarbarianAgilityCourse.java#L97">BarbarianAgilityCourse.onOpLoc</a>
     */
    BARBARIAN_AGILITY_LOG_676_MIDWAY(everyScenery(
            676,
            p -> midway(
                    p,
                    to(492, 563),
                    List.of(stand(489, 563, 489, 563), stand(490, 563, 490, 563)),
                    when(level(Skill.AGILITY, 35)),
                    note("a balance roll, always failed under 35; a slip lands you in the water")))),

    // The handler does nothing for a ledge tile off x 498.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/BarbarianAgilityCourse.java#L120
    BARBARIAN_AGILITY_LEDGE_678_BALANCE_ACROSS(everyScenery(
            678,
            p -> p.x() != 498
                    ? List.of()
                    : List.of(obstacle(
                            678,
                            p.x(),
                            p.z(),
                            to(501, 1506),
                            AgilityObstacles.COST,
                            when(level(Skill.AGILITY, 35)),
                            note("a balance roll, always failed under 35; a slip drops you to the ground floor"))))),

    BARBARIAN_AGILITY_HANDHOLDS_679_CLIMB_WALL(anyPlacement(679, to(497, 555), AgilityObstacles.COST)),

    WATCHTOWER_FORCEFIELD_LEVER(
            obstacleAnywhere(1014, to(492, 3521), AgilityObstacles.COST, when(questAt(WATCHTOWER, 10)))),

    UNDERGROUND_PASS_MUD_PILE_767_CLIMB_TO_MAIN_FLOOR(anyPlacement(767, to(685, 3420), AgilityObstacles.COST)),
    UNDERGROUND_PASS_MUD_PILE_841_CLIMB(anyPlacement(841, to(727, 3448), AgilityObstacles.COST)),
    UNDERGROUND_PASS_MUD_PILE_843_CLIMB(anyPlacement(843, to(753, 3481), AgilityObstacles.COST)),
    UNDERGROUND_PASS_MUD_PILE_844_CLIMB(anyPlacement(844, to(753, 3475), AgilityObstacles.COST)),
    UNDERGROUND_PASS_MUD_PILE_846_CLIMB(anyPlacement(846, to(740, 3476), AgilityObstacles.COST)),
    UNDERGROUND_PASS_MUD_PILE_847_CLIMB(anyPlacement(847, to(735, 3478), AgilityObstacles.COST)),
    UNDERGROUND_PASS_MUD_PILE_890_CLIMB_TO_OLD_STAIRWAY(anyPlacement(890, to(773, 3417), AgilityObstacles.COST)),

    UNDERGROUND_PASS_NORTH_STONE_STEP_889_CLIMB_TO_SURFACE(
            anyPlacement(889, to(766, 585), AgilityObstacles.COST, when(notQuestAt(UNDERGROUND_PASS, 4)))),
    UNDERGROUND_PASS_LEVER_733_LOWER_BRIDGE_CROSS_W(anyPlacement(733, to(703, 3420), AgilityObstacles.COST)),
    UNDERGROUND_PASS_DROP_DOWN_LEDGE_812_DROP_TO_CAVE_FLOOR(anyPlacement(812, to(706, 3439), AgilityObstacles.COST)),

    UNDERGROUND_PASS_SPEAR_ROCK_806(everyScenery(806, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_807(everyScenery(807, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_808(everyScenery(808, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_809(everyScenery(809, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_810(everyScenery(810, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_811(everyScenery(811, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_882(everyScenery(882, AgilityObstacles::spearRockArms)),
    UNDERGROUND_PASS_SPEAR_ROCK_883(everyScenery(883, AgilityObstacles::spearRockArms)),

    UP_STONE_BRIDGE_892(everyScenery(892, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_893(everyScenery(893, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_894(everyScenery(894, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_895(everyScenery(895, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_896(everyScenery(896, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_897(everyScenery(897, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_898(everyScenery(898, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_899(everyScenery(899, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_900(everyScenery(900, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_901(everyScenery(901, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_902(everyScenery(902, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_903(everyScenery(903, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_904(everyScenery(904, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_905(everyScenery(905, AgilityObstacles::bridgeFrom)),
    UP_STONE_BRIDGE_906(everyScenery(906, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_907(everyScenery(907, AgilityObstacles::bridgeFrom)),
    UP_STONE_BRIDGE_908(everyScenery(908, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_909(everyScenery(909, AgilityObstacles::bridgeArms)),
    UP_STONE_BRIDGE_910(everyScenery(910, AgilityObstacles::bridgeFrom)),

    UP_STONE_BRIDGE_891(everyScenery(
            891,
            p -> List.of(
                    scenery(
                            891,
                            p.plus(3, 0),
                            to(773, p.z()),
                            AgilityObstacles.COST,
                            clickAt(p.at()),
                            when(Prereq.all(level(Skill.AGILITY, 1), notQuestAt(UNDERGROUND_PASS, 4))),
                            canKillAt(6)),
                    scenery(
                            891,
                            p.plus(-1, 0),
                            to(776, p.z()),
                            AgilityObstacles.COST,
                            clickAt(p.at()),
                            when(Prereq.all(level(Skill.AGILITY, 1), notQuestAt(UNDERGROUND_PASS, 4))),
                            canKillAt(6))))),

    UP_BLACK_AREA_ROCK_849(everyScenery(849, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_850(everyScenery(850, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_851(everyScenery(851, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_852(everyScenery(852, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_853(everyScenery(853, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_854(everyScenery(854, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_855(everyScenery(855, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_857(everyScenery(857, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_858(everyScenery(858, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_859(everyScenery(859, p -> rockArms(p, 6))),
    UP_BLACK_AREA_ROCK_860(everyScenery(860, p -> rockArms(p, 6))),

    UP_HIJACK_ROCK_856(everyScenery(
            856,
            p -> List.of(
                    obstacle(
                            856,
                            p.x(),
                            p.z(),
                            to(735, 3479),
                            AgilityObstacles.COST,
                            xIn(734, 734),
                            when(level(Skill.AGILITY, 1)),
                            canKillAt(6)),
                    obstacle(
                            856,
                            p.x(),
                            p.z(),
                            to(734, 3480),
                            AgilityObstacles.COST,
                            xAtMost(733),
                            when(level(Skill.AGILITY, 1)),
                            canKillAt(6)),
                    obstacle(
                            856,
                            p.x(),
                            p.z(),
                            to(734, 3480),
                            AgilityObstacles.COST,
                            xAtLeast(735),
                            when(level(Skill.AGILITY, 1)),
                            canKillAt(6))))),

    UP_BLACK_AREA_MUD_845_CLIMB_SE_POCKET(anyPlacement(845, to(743, 3483), AgilityObstacles.COST)),

    UP_BLACK_AREA_LEDGE_837_JUMP_OFF(
            obstacleAnywhere(837, to(764, 3467), AgilityObstacles.COST, when(level(Skill.AGILITY, 1)), canKillAt(6))),

    UP_BLACK_AREA_LEDGE_837_CLIMB_UP(obstacleAnywhere(
            837,
            to(764, 3463),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 1)),
            secondCommand(),
            note("a soft roll; a slip retries in place"),
            canKillAt(1))),

    GNOME_AGILITY_COURSE_NET_CLIMB_UP(anyPlacement(647, to(692, 1448), AgilityObstacles.COST)),
    GNOME_AGILITY_COURSE_PIPE_EXIT(anyPlacement(654, to(683, 494), AgilityObstacles.COST)),

    GNOME_AGILITY_COURSE_BALANCE_LOG(anyPlacement(655, to(692, 499), AgilityObstacles.COST)),

    GNOME_AGILITY_COURSE_ROPE_SWING(anyPlacement(650, to(685, 2396), AgilityObstacles.COST)),

    GNOME_AGILITY_COURSE_WATCH_TOWER_CLIMB_UP(anyPlacement(648, to(693, 2394), AgilityObstacles.COST)),

    SHILO_TOMB_OF_BERVIRIUS_HANDHOLDS_CLIMB_TO_SURFACE(anyPlacement(
            690,
            to(471, 836),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 1)),
            note("a soft roll; a fall lands you at (467,3674)"))),

    // Carrying Ana in a barrel here gets you caught and jailed instead.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/Tourist_Trap_Mechanism.java#L372
    TOURIST_TRAP_MINING_CAVE_BACK(anyPlacement(
            964,
            to(84, 3640),
            AgilityObstacles.COST,
            when(Prereq.fact(Fact.notCarrying(ItemId.ANA_IN_A_BARREL.id()))))),
    SKAVID_MAZE_HOLE_YANILLE(anyPlacement(969, to(609, 742), AgilityObstacles.COST)),
    // The side is the player's absolute y; in, once the crystal is placed or the quest is done (the
    // reward clears the crystal's key).
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L156
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/npcs/LegendsQuestSirRadimusErkle.java#L57
    KHARAZI_CAVERNOUS_OPENING(everyScenery(
            1145,
            p -> List.of(
                    obstacle(1145, p.x(), p.z(), to(395, 3725), AgilityObstacles.COST, zAtLeast(3733)),
                    obstacle(
                            1145,
                            p.x(),
                            p.z(),
                            to(395, 3733),
                            AgilityObstacles.COST,
                            zAtMost(3732),
                            when(Prereq.any(cache("cavernous_opening"), questComplete(LEGENDS_QUEST))))))),

    /**
     * Into the mine's inner caves. Before the quest is complete the guard jails a player not wearing
     * both slave robes, and before stage 9 the way is blocked.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/Tourist_Trap_Mechanism.java#L383">Tourist_Trap_Mechanism.onOpLoc</a>
     */
    TOURIST_TRAP_MINING_CAVE(anyPlacement(
            963,
            to(76, 3640),
            AgilityObstacles.COST,
            when(Prereq.any(
                    questComplete(TOURIST_TRAP),
                    Prereq.all(
                            questAtLeast(TOURIST_TRAP, 9),
                            wearing(ItemId.SLAVES_ROBE_TOP.id()),
                            wearing(ItemId.SLAVES_ROBE_BOTTOM.id())))))),

    // Without the map, or without a light, a cave lands you elsewhere.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L208
    GU_TANOTH_SKAVID_CAVE_970_650_3555(anyPlacement(
            970,
            to(650, 3555),
            AgilityObstacles.COST,
            when(Prereq.all(carrying(ItemId.SKAVID_MAP.id(), 1), Kit.LIT_LIGHT)))),
    GU_TANOTH_SKAVID_CAVE_972_626_3564(anyPlacement(
            972,
            to(626, 3564),
            AgilityObstacles.COST,
            when(Prereq.all(carrying(ItemId.SKAVID_MAP.id(), 1), Kit.LIT_LIGHT)))),
    GU_TANOTH_SKAVID_CAVE_950_627_3591(anyPlacement(
            950,
            to(627, 3591),
            AgilityObstacles.COST,
            when(Prereq.all(carrying(ItemId.SKAVID_MAP.id(), 1), Kit.LIT_LIGHT)))),
    GU_TANOTH_SKAVID_CAVE_971_638_3564(anyPlacement(
            971,
            to(638, 3564),
            AgilityObstacles.COST,
            when(Prereq.all(carrying(ItemId.SKAVID_MAP.id(), 1), Kit.LIT_LIGHT)))),
    GU_TANOTH_SKAVID_CAVE_949_629_3574(anyPlacement(
            949,
            to(629, 3574),
            AgilityObstacles.COST,
            when(Prereq.all(carrying(ItemId.SKAVID_MAP.id(), 1), Kit.LIT_LIGHT)))),
    GU_TANOTH_SKAVID_CAVE_975_647_3596(anyPlacement(
            975,
            to(647, 3596),
            AgilityObstacles.COST,
            when(Prereq.all(carrying(ItemId.SKAVID_MAP.id(), 1), Kit.LIT_LIGHT)))),

    LEGENDS_ROPE_UP(anyPlacement(1167, to(471, 3707), AgilityObstacles.COST)),
    LEGENDS_CAVE_EXIT_TO_SURFACE(anyPlacement(1158, to(452, 874), AgilityObstacles.COST)),
    OGRE_ENCLAVE_EXIT(anyPlacement(1024, to(662, 788), AgilityObstacles.COST)),
    WATERFALL_JUMP_OFF_BAXTORIAN(anyPlacement(469, to(654, 485), AgilityObstacles.COST, canKillAt(10))),

    LEGENDS_CAVE_ENTRANCE_FROM_BOULDERS(
            obstacleAnywhere(1159, to(452, 3702), AgilityObstacles.COST, answers("climb into it"))),

    GNOME_AGILITY_COURSE_SECOND_NET_CLIMB_UP(anyPlacement(653, to(683, 501), AgilityObstacles.COST)),
    WILDERNESS_AGILITY_COURSE_PIPE_SQUEEZE(anyPlacement(705, to(294, 112), AgilityObstacles.COST)),
    UNDERGROUND_PASS_SW_ROPE_CLIMB(anyPlacement(817, to(737, 3453), AgilityObstacles.COST)),

    WATERFALL_LOG_RAFT_BOARD_ISLAND(anyPlacement(464, to(662, 463), AgilityObstacles.COST)),

    /**
     * Off the island's dead trees into the rapids. Every tree row fires from the tree's own tile: the
     * raft lands the player on tree 462 and the rope from each tree onto the next.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L327">Waterfall_Quest.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L756">Waterfall_Quest.onUseLoc</a>
     */
    WATERFALL_ISLAND_TREE_462_JUMP_OFF(everyScenery(
            462,
            p -> List.of(scenery(462, p.at(), to(654, 485), AgilityObstacles.COST, clickAt(p.at()), canKillAt(10))))),
    WATERFALL_ISLAND_TREE_463_JUMP_OFF(everyScenery(
            463,
            p -> List.of(scenery(463, p.at(), to(654, 485), AgilityObstacles.COST, clickAt(p.at()), canKillAt(10))))),
    WATERFALL_ISLAND_TREE_482_JUMP_OFF(everyScenery(
            482,
            p -> List.of(scenery(482, p.at(), to(654, 485), AgilityObstacles.COST, clickAt(p.at()), canKillAt(10))))),

    WATERFALL_ISLAND_ROPE_TREE_462(everyScenery(
            462,
            p -> List.of(scenery(
                    462,
                    p.at(),
                    to(662, 467),
                    AgilityObstacles.COST,
                    usesItem(ItemId.ROPE.id(), 0),
                    clickAt(p.at()))))),
    WATERFALL_ISLAND_ROPE_TREE_463(everyScenery(
            463,
            p -> List.of(scenery(
                    463,
                    p.at(),
                    to(659, 471),
                    AgilityObstacles.COST,
                    usesItem(ItemId.ROPE.id(), 0),
                    clickAt(p.at()))))),
    WATERFALL_ROPE_TREE_482_BAXTORIAN_CAVE(everyScenery(
            482,
            p -> List.of(scenery(
                    482,
                    p.at(),
                    to(659, 3305),
                    AgilityObstacles.COST,
                    usesItem(ItemId.ROPE.id(), 0),
                    clickAt(p.at()))))),

    /**
     * The rocks outside the mining camp's cell, climbed from the tile the bent window bar drops the
     * player on. A slip costs 7 but lands there all the same.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L3350">TouristTrap.attemptBendBar</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L2751">TouristTrap.onOpLoc</a>
     */
    TOURIST_TRAP_ROCKY_ELEVATION_953_CLIMB(everyScenery(
            953,
            p -> List.of(
                    scenery(953, p.plus(-1, 1), to(93, 799), AgilityObstacles.COST, clickAt(p.at()), canKillAt(7))))),

    /**
     * The Viyeldi caves' rock-hewn stairs (1114, 1123, 1124, 1125) and rocky walkways (558 to 561): each
     * matched by id, a stair's side the player's absolute x or y. Each is a roll at Agility 50; a stair's
     * slip lands where its descent would, a walkway's drops the player into the main caves.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestCaveAgility.java#L164">LegendsQuestCaveAgility.onOpLoc</a>
     */
    LEGENDS_CAVE_ROCK_HEWN_STAIRS_1114(everyScenery(
            1114,
            p -> List.of(
                    obstacle(
                            1114,
                            p.x(),
                            p.z(),
                            to(426, 3702),
                            AgilityObstacles.COST,
                            zAtLeast(3706),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3)),
                    obstacle(
                            1114,
                            p.x(),
                            p.z(),
                            to(426, 3706),
                            AgilityObstacles.COST,
                            zAtMost(3705),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3))))),

    LEGENDS_CAVE_ROCK_HEWN_STAIRS_1123(everyScenery(
            1123,
            p -> List.of(
                    scenery(
                            1123,
                            p.plus(3, 1),
                            to(422, 3702),
                            AgilityObstacles.COST,
                            clickAt(p.at()),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3)),
                    scenery(
                            1123,
                            p.plus(-1, 1),
                            to(426, 3702),
                            AgilityObstacles.COST,
                            clickAt(p.at()),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3))))),

    LEGENDS_CAVE_ROCKY_WALKWAY_558(everyScenery(558, p -> walkway(p, -1))),
    LEGENDS_CAVE_ROCKY_WALKWAY_559(everyScenery(559, p -> walkway(p, 1, -1))),
    LEGENDS_CAVE_ROCKY_WALKWAY_560(everyScenery(560, p -> walkway(p, 1, -1))),
    LEGENDS_CAVE_ROCKY_WALKWAY_561(everyScenery(561, p -> walkway(p, 1))),

    LEGENDS_CAVE_ROCK_HEWN_STAIRS_1124(everyScenery(
            1124,
            p -> List.of(
                    scenery(
                            1124,
                            p.plus(1, -1),
                            to(419, 3706),
                            AgilityObstacles.COST,
                            clickAt(p.at()),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3)),
                    scenery(
                            1124,
                            p.plus(1, 3),
                            to(419, 3702),
                            AgilityObstacles.COST,
                            clickAt(p.at()),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3))))),

    LEGENDS_CAVE_ROCK_HEWN_STAIRS_1125(everyScenery(
            1125,
            p -> List.of(
                    obstacle(
                            1125,
                            p.x(),
                            p.z(),
                            to(423, 3707),
                            AgilityObstacles.COST,
                            xAtMost(419),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3)),
                    obstacle(
                            1125,
                            p.x(),
                            p.z(),
                            to(419, 3707),
                            AgilityObstacles.COST,
                            xAtLeast(420),
                            when(level(Skill.AGILITY, 50)),
                            canKillAt(3))))),

    BARBARIAN_AGILITY_COURSE_PIPE(everyScenery(671, AgilityObstacles::barbarianPipe)),
    BARBARIAN_AGILITY_COURSE_BACK_PIPE(everyScenery(672, AgilityObstacles::barbarianPipe)),

    WILDERNESS_AGILITY_COURSE_ROPE_SWING(anyPlacement(
            706,
            to(292, 108),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 52)),
            note("a soft roll; a fall lands you at (293,2942)"))),

    WILDERNESS_AGILITY_COURSE_STEPPING_STONES(anyPlacement(
            707,
            to(297, 106),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 52)),
            note("a soft roll; a fall lands you at (292,104)"))),
    WILDERNESS_AGILITY_COURSE_LEDGE(anyPlacement(
            708,
            to(301, 111),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 52)),
            note("a soft roll; a fall lands you at (298,2945)"))),

    /**
     * The stones again from where their handler stands the player between steps; (293,105) and (296,105)
     * walk off instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/WildernessAgilityCourse.java#L134">WildernessAgilityCourse.onOpLoc</a>
     */
    WILDERNESS_AGILITY_COURSE_STEPPING_STONES_MIDWAY(everyScenery(
            707,
            p -> midway(
                    p,
                    to(297, 106),
                    List.of(stand(294, 104, 294, 104), stand(295, 104, 295, 104)),
                    when(level(Skill.AGILITY, 52)),
                    note("a soft roll; a fall lands you at (292,104)")))),

    /**
     * The ledge again from where its handler stands the player between steps; (296,112) walks off
     * instead. From (300,111) no ledge is in reach and no step leads off, so nothing leaves it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/WildernessAgilityCourse.java#L152">WildernessAgilityCourse.onOpLoc</a>
     */
    WILDERNESS_AGILITY_COURSE_LEDGE_MIDWAY(everyScenery(
            708,
            p -> midway(
                    p,
                    to(301, 111),
                    List.of(stand(297, 112, 297, 112), stand(298, 112, 298, 112), stand(299, 111, 298, 111)),
                    when(level(Skill.AGILITY, 52)),
                    note("a soft roll; a fall lands you at (298,2945)")))),

    WATCHTOWER_OGRE_CHASM_JUMP_BACK(
            anyPlacement(996, to(646, 805), AgilityObstacles.COST, when(level(Skill.AGILITY, 30)))),

    /**
     * The jump over the chasm, paying the bridge guard's toll of 20 coins. With no guard within 5
     * tiles there is nothing to pay; the toll is planned all the same.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L342">WatchTowerObstacles.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L357">WatchTowerObstacles.onOpLoc</a>
     */
    WATCHTOWER_OGRE_CHASM_JUMP_OVER_TOLL(obstacleAnywhere(
            995,
            to(647, 799),
            AgilityObstacles.COST,
            when(level(Skill.AGILITY, 30)),
            pays(ItemId.COINS.id(), 20),
            answers("pay it"))),
    WATCHTOWER_TUNNEL_CAVE(anyPlacement(998, to(605, 803), AgilityObstacles.COST)),

    MAGE_ARENA_BARRIER(everyScenery(
            1027,
            p -> List.of(
                    obstacle(1027, p.x(), p.z(), to(228, 118), AgilityObstacles.COST, zAtLeast(120)),
                    obstacle(
                            1027,
                            p.x(),
                            p.z(),
                            to(228, 120),
                            AgilityObstacles.COST,
                            zAtMost(119),
                            // Kolodion's permission, then no banned item carried or worn.
                            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/mage_arena/MageArena.java#L677
                            when(Prereq.all(
                                    cacheAtLeast("mage_arena", 4), Prereq.fact(Fact.notCarryingMageArenaBanned()))))))),

    GREW_ISLAND_ROPE_SWING_TIE_ROPE_MAINLAND_ISLAND(bespoke(GrewIslandRopeSwingTransport::forWorld)),
    LEGENDS_VIYELDI_ROPE_DOWN(bespoke(ViyeldiRopeDownTransport::forWorld)),

    // The handler serves these ids on every world, but only location_data 2 places them.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L213
    TAVERLEY_DUNGEON_PIPE_W_E(onlyIn(
            locationData(2), anyPlacement(1236, to(375, 3352), AgilityObstacles.COST, when(level(Skill.AGILITY, 70))))),
    TAVERLEY_DUNGEON_PIPE_E_W(onlyIn(
            locationData(2), anyPlacement(1237, to(372, 3352), AgilityObstacles.COST, when(level(Skill.AGILITY, 70))))),

    ENTRANA_RUBBLE(onlyIn(
            locationData(2),
            everyScenery(
                    1286,
                    p -> List.of(
                            scenery(
                                    1286,
                                    p.plus(0, -1),
                                    to(434, 551),
                                    AgilityObstacles.COST,
                                    clickAt(p.at()),
                                    when(level(Skill.AGILITY, 55))),
                            scenery(
                                    1286,
                                    p.plus(0, 1),
                                    to(434, 549),
                                    AgilityObstacles.COST,
                                    clickAt(p.at()),
                                    when(level(Skill.AGILITY, 55))))))),

    /**
     * A flat 1 in 10 fall into the river for a fifth of maximum Hits; a fall that kills leaves the body
     * on the bank it left.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L616">AgilityShortcuts.onOpLoc</a>
     */
    TAVERLEY_CATHERBY_STEPPING_STONES(onlyIn(
            locationData(2),
            anyPlacement(
                    1287,
                    to(397, 502),
                    AgilityObstacles.COST,
                    when(level(Skill.AGILITY, 50)),
                    note("a soft roll (10%); a fall lands you at (388,522)"),
                    canKillAt(19)))),
    /** The same stones the other way: see {@link #TAVERLEY_CATHERBY_STEPPING_STONES}. */
    CATHERBY_TAVERLEY_STEPPING_STONES(onlyIn(
            locationData(2),
            anyPlacement(
                    1288,
                    to(395, 502),
                    AgilityObstacles.COST,
                    when(level(Skill.AGILITY, 50)),
                    note("a soft roll (10%); a fall lands you at (388,522)"),
                    canKillAt(19)))),

    /**
     * Either way across again from the mid-river stone both handlers stand the player on.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L606">AgilityShortcuts.onOpLoc, Taverley side</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L643">AgilityShortcuts.onOpLoc, Catherby side</a>
     */
    TAVERLEY_CATHERBY_STEPPING_STONES_MIDWAY(onlyIn(
            locationData(2),
            everyScenery(
                    1287,
                    p -> midway(
                            p,
                            to(397, 502),
                            List.of(stand(396, 502, 395, 502)),
                            when(level(Skill.AGILITY, 50)),
                            note("a soft roll (10%); a fall lands you at (388,522)"),
                            canKillAt(19))))),
    /** See {@link #TAVERLEY_CATHERBY_STEPPING_STONES_MIDWAY}. */
    CATHERBY_TAVERLEY_STEPPING_STONES_MIDWAY(onlyIn(
            locationData(2),
            everyScenery(
                    1288,
                    p -> midway(
                            p,
                            to(395, 502),
                            List.of(stand(396, 502, 397, 502)),
                            when(level(Skill.AGILITY, 50)),
                            note("a soft roll (10%); a fall lands you at (388,522)"),
                            canKillAt(19))))),

    FALADOR_MEMBERS_WALL_HANDHOLDS(onlyIn(
            locationData(2), anyPlacement(1290, to(339, 544), AgilityObstacles.COST, when(level(Skill.AGILITY, 40))))),

    // A slip into the lava costs 21% of current Hits, which never kills.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L703
    KING_BLACK_DRAGON_LAVA_STONES_TO_LAVA_DUNGEON(onlyIn(
            locationData(2),
            anyPlacement(
                    1291,
                    to(272, 3012),
                    AgilityObstacles.LAVA_STONES_COST,
                    when(level(Skill.AGILITY, 67)),
                    note("a soft roll; a slip lands you back at (281,3016)")))),
    LAVA_DUNGEON_LAVA_STONES_TO_KING_BLACK_DRAGON(onlyIn(
            locationData(2),
            anyPlacement(
                    1292,
                    to(281, 3015),
                    AgilityObstacles.LAVA_STONES_COST,
                    when(level(Skill.AGILITY, 67)),
                    note("a soft roll; a slip lands you back at (271,3012)")))),

    // The side is the player's absolute y; a slip costs 20% of current Hits, which never kills.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L761
    SHILO_RIVER_STEPPING_STONE(onlyIn(
            locationData(2),
            everyScenery(
                    1295,
                    p -> List.of(
                            scenery(
                                    1295,
                                    p.plus(-1, 1),
                                    to(369, 829),
                                    AgilityObstacles.COST,
                                    clickAt(p.at()),
                                    when(level(Skill.AGILITY, 85)),
                                    note("a soft roll; a slip lands you at (383,836)")),
                            scenery(
                                    1295,
                                    p.plus(1, -1),
                                    to(367, 831),
                                    AgilityObstacles.COST,
                                    clickAt(p.at()),
                                    when(level(Skill.AGILITY, 85)),
                                    note("a soft roll; a slip lands you at (383,833)")))))),

    LEGENDS_VIYELDI_ROPE_DOWN_AFTER_QUEST(
            onlyIn(rule(ServerRules::postQuestRegionsOpen), bespoke(ViyeldiRopeDownTransport::afterQuest))),
    ;

    /**
     * Above a ladder's 30 because many obstacles roll and a fall costs a retry. Rows write it qualified:
     * an enum constant's arguments cannot name a static field of its own enum by its simple name.
     */
    static final int COST = 45;

    /**
     * The lava stones with their slips folded in: at the Agility 67 gate about 48% of jumps hold, so a
     * crossing takes about 2.1 jumps. A stronger jumper overpays a little.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L687">AgilityShortcuts.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L358">Formulae.calcProductionSuccessfulLegacy</a>
     */
    static final int LAVA_STONES_COST = 210;

    /** West, east, north, south of a placement: the tiles a handler's exact-tile tests name. */
    private static final int[][] SIDES = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /**
     * Where the rock climb puts a player standing {@code (dx, dz)} from rock {@code p}, or null where
     * it moves no one. The landing turns on the rock's direction; rock 749 alone drops a north or south
     * climber straight across.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap1.java#L338">UndergroundPassObstaclesMap1.doRock</a>
     */
    private static Point rockLanding(Placed p, int dx, int dz) {
        boolean east = dx == 1, west = dx == -1, north = dz == -1, south = dz == 1;
        return switch (p.dir()) {
            case 1, 2, 3, 4 ->
                east
                        ? p.plus(-1, 0)
                        : west
                                ? p.plus(1, 0)
                                : north
                                        ? (p.objectId() == 749 ? p.plus(0, 1) : p.plus(1, 0))
                                        : south ? (p.objectId() == 749 ? p.plus(0, -1) : p.plus(1, 0)) : null;
            case 6 -> north ? p.plus(0, 1) : south ? p.plus(0, -1) : east ? p.plus(1, 1) : west ? p.plus(0, 1) : null;
            case 0 -> east ? p.plus(-1, 0) : west ? p.plus(1, 0) : north ? p.plus(0, 1) : south ? p.plus(0, -1) : null;
            case 7 -> east ? p.plus(-1, -1) : west ? p.plus(1, 0) : north ? p.plus(0, 1) : south ? p.plus(1, 0) : null;
            default -> null;
        };
    }

    /**
     * Rock {@code p} climbed from each side the climb serves. A slip costs {@code H/42 + 1} on the main
     * floor and {@code H/5 + 5} in the black area.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap1.java#L131">UndergroundPassObstaclesMap1.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L167">UndergroundPassObstaclesMap2.onOpLoc</a>
     */
    private static List<TransportSpec> rockArms(Placed p, int canKillAt) {
        List<TransportSpec> out = new ArrayList<>();
        for (int[] s : SIDES) {
            Point to = rockLanding(p, s[0], s[1]);
            if (to != null) {
                out.add(obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        to,
                        AgilityObstacles.COST,
                        onlyFrom(p.x() + s[0], p.z() + s[1]),
                        when(level(Skill.AGILITY, 1)),
                        canKillAt(canKillAt)));
            }
        }
        return out;
    }

    /** A tile a crossing's handler stands the player on between steps, and the placement clicked from it. */
    private record Stand(Point on, Point click) {}

    private static Stand stand(int x, int z, int clickX, int clickZ) {
        return new Stand(Point.of(x, z), Point.of(clickX, clickZ));
    }

    /**
     * Placement {@code p} clicked from each of {@code stands} that names it, landing on {@code landing}:
     * the whole crossing again. No step leads off these tiles; the server takes the click from a
     * placement underfoot or beside.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L197">Mob.atObject</a>
     */
    private static List<TransportSpec> midway(Placed p, Point landing, List<Stand> stands, Option... options) {
        Option[] clicked = Arrays.copyOf(options, options.length + 1);
        clicked[options.length] = clickAt(p.at());
        List<TransportSpec> out = new ArrayList<>();
        for (Stand s : stands) {
            if (s.click().equals(p.at())) {
                out.add(scenery(p.objectId(), s.on(), landing, AgilityObstacles.COST, clicked));
            }
        }
        return out;
    }

    /** Rock {@code p} climbed from each stated side {@code (dx, dz)}, landing as {@link #rockLanding}. */
    private static List<TransportSpec> rockFrom(Placed p, int canKillAt, int[]... sides) {
        List<TransportSpec> out = new ArrayList<>();
        for (int[] s : sides) {
            out.add(scenery(
                    p.objectId(),
                    p.plus(s[0], s[1]),
                    rockLanding(p, s[0], s[1]),
                    AgilityObstacles.COST,
                    when(level(Skill.AGILITY, 1)),
                    clickAt(p.at()),
                    canKillAt(canKillAt)));
        }
        return out;
    }

    /**
     * Ledge {@code p} crossed from either side: one facing 2 or 6 lands one tile past it (two for 753),
     * one facing 0 or 4 one tile past. A slip costs {@code H/42 + 1}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap1.java#L299">UndergroundPassObstaclesMap1.doLedge</a>
     */
    private static List<TransportSpec> ledgeArms(Placed p) {
        int past = p.objectId() == 753 ? 2 : 1;
        List<TransportSpec> out = new ArrayList<>();
        if (p.dir() == 2 || p.dir() == 6) {
            out.add(ledge(p, p.plus(-1, 0), p.plus(past, 0)));
            out.add(ledge(p, p.plus(1, 0), p.plus(-past, 0)));
        } else if (p.dir() == 0 || p.dir() == 4) {
            out.add(ledge(p, p.plus(0, -1), p.plus(0, 1)));
            out.add(ledge(p, p.plus(0, 1), p.plus(0, -1)));
        }
        return out;
    }

    private static TransportSpec ledge(Placed p, Point from, Point to) {
        return obstacle(
                p.objectId(),
                p.x(),
                p.z(),
                to,
                AgilityObstacles.COST,
                onlyFrom(from.x(), from.y()),
                when(level(Skill.AGILITY, 1)),
                canKillAt(1));
    }

    /**
     * Spear rock {@code p} searched and disarmed: from the column west of it the player lands one tile
     * east, from anywhere else one tile west. A failed disarm springs the trap for {@code H/6 + 1}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap1.java#L252">UndergroundPassObstaclesMap1.onOpLoc</a>
     */
    private static List<TransportSpec> spearRockArms(Placed p) {
        return List.of(
                obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        p.plus(1, 0),
                        AgilityObstacles.COST,
                        xIn(p.x() - 1, p.x() - 1),
                        when(level(Skill.THIEVING, 1)),
                        answers("yes"),
                        secondCommand(),
                        note("a disarm roll; retried until it holds"),
                        canKillAt(1)),
                obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        p.plus(-1, 0),
                        AgilityObstacles.COST,
                        xAtLeast(p.x()),
                        when(level(Skill.THIEVING, 1)),
                        answers("yes"),
                        secondCommand(),
                        note("a disarm roll; retried until it holds"),
                        canKillAt(1)));
    }

    /**
     * Stone bridge {@code p} jumped or crossed: from the column west of it the player lands three tiles
     * east of its tile, from three east of it one tile west; likewise north and south. A slip drops the
     * player to the floor below for {@code H/5 + 5}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassAgilityObstacles.java#L108">UndergroundPassAgilityObstacles.onOpLoc</a>
     */
    private static List<TransportSpec> bridgeArms(Placed p) {
        int x = p.x(), z = p.z();
        return List.of(
                bridge(p, p.plus(3, 0), xIn(x - 1, x - 1)),
                bridge(p, p.plus(-1, 0), xIn(x + 3, x + 3)),
                bridge(p, p.plus(0, 3), xIn(x, x + 2), zIn(z - 1, z - 1)),
                bridge(p, p.plus(0, -1), xIn(x, x + 2), zIn(z + 3, z + 3)));
    }

    private static TransportSpec bridge(Placed p, Point to, Option... side) {
        List<Option> options = new ArrayList<>(List.of(side));
        options.add(when(level(Skill.AGILITY, 1)));
        options.add(canKillAt(6));
        return obstacle(p.objectId(), p.x(), p.z(), to, AgilityObstacles.COST, options.toArray(Option[]::new));
    }

    /**
     * Stone bridge {@code p} from the tile past each end of its span, landing as {@link #bridgeArms}.
     *
     * @throws IllegalStateException when the bridge faces other than 0 or 6
     */
    private static List<TransportSpec> bridgeFrom(Placed p) {
        List<Point[]> ends =
                switch (p.dir()) {
                    case 6 ->
                        List.of(new Point[] {p.plus(-1, 0), p.plus(3, 0)}, new Point[] {p.plus(3, 0), p.plus(-1, 0)});
                    case 0 ->
                        List.of(new Point[] {p.plus(0, -1), p.plus(0, 3)}, new Point[] {p.plus(0, 3), p.plus(0, -1)});
                    default ->
                        throw new IllegalStateException("stone bridge " + p.objectId() + " @" + p.at() + " faces "
                                + p.dir() + "; bridgeFrom knows 0 and 6");
                };
        List<TransportSpec> out = new ArrayList<>();
        for (Point[] e : ends) {
            out.add(scenery(
                    p.objectId(),
                    e[0],
                    e[1],
                    AgilityObstacles.COST,
                    when(level(Skill.AGILITY, 1)),
                    clickAt(p.at()),
                    canKillAt(6)));
        }
        return out;
    }

    /**
     * A west-passage trap rock cleared from the east: from two columns east of it the player lands one
     * tile west of it on row 3446; from elsewhere the trap throws them back.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassOrbs.java#L75">UndergroundPassOrbs.onOpLoc</a>
     */
    private static TransportSpec westPassageClear(Placed p) {
        return obstacle(
                p.objectId(), p.x(), p.z(), to(p.x() - 1, 3446), AgilityObstacles.COST, xIn(p.x() + 2, p.x() + 2));
    }

    /**
     * The same rock's trip wire stepped over, from either side. A brush (3 in 20) throws the player back
     * through every trap to the passage's mouth, so the deeper the rock the more it can cost.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassOrbs.java#L89">UndergroundPassOrbs.onOpLoc</a>
     */
    private static List<TransportSpec> westPassageStepOver(Placed p, int canKillAt) {
        return List.of(
                obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        to(p.x() + 1, 3446),
                        AgilityObstacles.COST,
                        xIn(p.x() - 1, p.x() - 1),
                        answers("step over"),
                        secondCommand(),
                        canKillAt(canKillAt)),
                obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        to(p.x() - 1, 3446),
                        AgilityObstacles.COST,
                        xIn(p.x() + 2, p.x() + 2),
                        answers("step over"),
                        secondCommand(),
                        canKillAt(canKillAt)));
    }

    /**
     * Either end of the Barbarian course's pipe; the side is the player's absolute y.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/BarbarianAgilityCourse.java#L55">BarbarianAgilityCourse.onOpLoc</a>
     */
    private static List<TransportSpec> barbarianPipe(Placed p) {
        return List.of(
                obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        to(487, 554),
                        AgilityObstacles.COST,
                        zAtMost(551),
                        when(level(Skill.AGILITY, 35))),
                obstacle(
                        p.objectId(),
                        p.x(),
                        p.z(),
                        to(487, 551),
                        AgilityObstacles.COST,
                        zAtLeast(552),
                        when(level(Skill.AGILITY, 35))));
    }

    /**
     * Walkway stone {@code p} stepped onto from each stated neighbour: the player lands on the stone's
     * own tile, or slips into the main caves for up to 31.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestCaveAgility.java#L42">LegendsQuestCaveAgility.onOpLoc</a>
     */
    private static List<TransportSpec> walkway(Placed p, int... dxs) {
        List<TransportSpec> out = new ArrayList<>();
        for (int dx : dxs) {
            out.add(scenery(
                    p.objectId(),
                    p.plus(dx, 0),
                    p.at(),
                    AgilityObstacles.COST,
                    clickAt(p.at()),
                    when(level(Skill.AGILITY, 50)),
                    canKillAt(31)));
        }
        return out;
    }

    // A spec may hold arrays, lists or a lambda, which the checker cannot prove immutable; each is
    // built once here and never changed.
    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    AgilityObstacles(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (AgilityObstacles a : values()) {
            out.addAll(a.expand(env));
        }
        return out;
    }
}
