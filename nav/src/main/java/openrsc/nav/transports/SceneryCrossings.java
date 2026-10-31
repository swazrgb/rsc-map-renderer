package openrsc.nav.transports;

import static openrsc.bot.api.quest.QuestId.BIOHAZARD;
import static openrsc.bot.api.quest.QuestId.DIGSITE;
import static openrsc.bot.api.quest.QuestId.FISHING_CONTEST;
import static openrsc.bot.api.quest.QuestId.GRAND_TREE;
import static openrsc.bot.api.quest.QuestId.LEGENDS_QUEST;
import static openrsc.bot.api.quest.QuestId.OBSERVATORY_QUEST;
import static openrsc.bot.api.quest.QuestId.SHILO_VILLAGE;
import static openrsc.bot.api.quest.QuestId.TREE_GNOME_VILLAGE;
import static openrsc.bot.api.quest.QuestId.UNDERGROUND_PASS;
import static openrsc.bot.api.quest.QuestId.WATCHTOWER;
import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.awaitsDialog;
import static openrsc.nav.transports.spec.Rows.bySide;
import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.cacheAtLeast;
import static openrsc.nav.transports.spec.Rows.carrying;
import static openrsc.nav.transports.spec.Rows.clickAt;
import static openrsc.nav.transports.spec.Rows.from;
import static openrsc.nav.transports.spec.Rows.level;
import static openrsc.nav.transports.spec.Rows.notQuestAt;
import static openrsc.nav.transports.spec.Rows.notQuestAtLeast;
import static openrsc.nav.transports.spec.Rows.notQuestComplete;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.pays;
import static openrsc.nav.transports.spec.Rows.playerXAtLeast;
import static openrsc.nav.transports.spec.Rows.playerXAtMost;
import static openrsc.nav.transports.spec.Rows.playerYAtLeast;
import static openrsc.nav.transports.spec.Rows.playerYAtMost;
import static openrsc.nav.transports.spec.Rows.questAt;
import static openrsc.nav.transports.spec.Rows.questAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.secondCommand;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.wearing;
import static openrsc.nav.transports.spec.Rows.when;
import static openrsc.nav.transports.spec.SceneryCrossing.scenery;
import static openrsc.nav.transports.spec.SceneryCrossing.sceneryMenu;
import static openrsc.nav.transports.spec.WorldGate.feature;
import static openrsc.nav.transports.spec.WorldGate.rule;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.SceneryId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Scenery the server moves the player across or away from when operated: gates its handler sides by
 * the player's tile, ships, carts, rafts, pools and winches. One constant per direction wherever the
 * two directions differ in gate or landing.
 */
public enum SceneryCrossings implements TransportDef {
    // Either half of the ship sails to the port the captain's menu names.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/LadyOfTheWaves.java#L52
    LADY_OF_THE_WAVES_PORT_KHAZARD(everyScenery(
            SceneryId.SHIP_FRONT_LADY_WAVES_KARAMJA_JUNGLE.id(),
            p -> List.of(sceneryMenu(
                    SceneryId.SHIP_FRONT_LADY_WAVES_KARAMJA_JUNGLE.id(),
                    p.plus(0, 3),
                    to(545, 703),
                    120,
                    clickAt(p.at()),
                    pays(ItemId.SHIP_TICKET.id(), 1),
                    answers("khazard"))))),
    LADY_OF_THE_WAVES_PORT_SARIM(everyScenery(
            SceneryId.SHIP_FRONT_LADY_WAVES_KARAMJA_JUNGLE.id(),
            p -> List.of(sceneryMenu(
                    SceneryId.SHIP_FRONT_LADY_WAVES_KARAMJA_JUNGLE.id(),
                    p.plus(0, 3),
                    to(269, 640),
                    120,
                    clickAt(p.at()),
                    pays(ItemId.SHIP_TICKET.id(), 1),
                    answers("port sarim"))))),

    LADY_OF_THE_WAVES_PORT_KHAZARD_BACK_HALF_781(everyScenery(
            781,
            p -> List.of(sceneryMenu(
                    781,
                    p.plus(-1, 3),
                    to(545, 703),
                    120,
                    clickAt(p.at()),
                    pays(ItemId.SHIP_TICKET.id(), 1),
                    answers("khazard"))))),
    LADY_OF_THE_WAVES_PORT_SARIM_BACK_HALF_781(everyScenery(
            781,
            p -> List.of(sceneryMenu(
                    781,
                    p.plus(-1, 3),
                    to(269, 640),
                    120,
                    clickAt(p.at()),
                    pays(ItemId.SHIP_TICKET.id(), 1),
                    answers("port sarim"))))),

    // Only the ladder at (98,3537), and only while its fairy attendant is there.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L248
    ZANARIS_EXIT_LADDER(sceneryMenu(249, from(98, 3536), to(98, 706), 80, clickAt(98, 3537), answers("yes"))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/mining/Mining.java#L60
    ROCKSLIDE_MINE_THROUGH(everyScenery(
            SceneryId.ROCKSLIDE.id(),
            p -> bySide(
                    playerXAtMost(425),
                    (from, west) -> scenery(
                            SceneryId.ROCKSLIDE.id(),
                            from,
                            west ? to(428, 438) : to(425, 438),
                            60,
                            clickAt(p.at()),
                            when(Prereq.all(level(Skill.MINING, 50), Kit.PICKAXE))),
                    p.plus(-1, 0),
                    p.plus(1, 0)))),

    // Only the gate at (394,851), from x >= 394.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1256
    SHILO_VILLAGE_WOODEN_GATE_EXIT(scenery(712, from(394, 851), to(381, 851), 80)),
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1270
    SHILO_VILLAGE_METAL_GATE_ENTER(
            scenery(611, from(387, 851), to(395, 851), 80, clickAt(388, 851), when(questComplete(SHILO_VILLAGE)))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/SewerValve.java#L72
    HAZEEL_CULT_SEWER_RAFT(everyScenery(
            432,
            p -> List.of(scenery(
                    432,
                    p.plus(-1, 0),
                    to(587, 3411),
                    80,
                    clickAt(p.at()),
                    when(Prereq.all(
                            cache("VALVE_1_RIGHT"),
                            cache("VALVE_2_LEFT"),
                            cache("VALVE_3_RIGHT"),
                            cache("VALVE_4_RIGHT"),
                            cache("VALVE_5_LEFT"))))))),

    // From stage 15 the stand drops the player at once; at stage 14 it asks first.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L1583
    GRAND_TREE_STONE_STAND_PUSH_DOWN_FINALE(everyScenery(
            634,
            p -> List.of(sceneryMenu(
                    634,
                    p.plus(1, 0),
                    to(711, 3306),
                    90,
                    clickAt(p.at()),
                    when(questAtLeast(GRAND_TREE, 15)),
                    secondCommand())))),

    GRAND_TREE_STONE_STAND_PUSH_DOWN_DEMON(everyScenery(
            634,
            p -> List.of(sceneryMenu(
                    634,
                    p.plus(1, 0),
                    to(711, 3306),
                    90,
                    clickAt(p.at()),
                    when(questAt(GRAND_TREE, 14)),
                    answers("climb down"),
                    secondCommand())))),

    GRAND_TREE_PUSH_ROOT_IN(everyScenery(
            638,
            p -> List.of(
                    scenery(638, p.plus(0, 1), to(701, 3278), 80, clickAt(p.at()), when(questComplete(GRAND_TREE)))))),
    GRAND_TREE_PUSH_ROOT_OUT(everyScenery(
            639,
            p -> List.of(
                    scenery(639, p.plus(0, -1), to(700, 3280), 80, clickAt(p.at()), when(questComplete(GRAND_TREE)))))),

    // The first climb from y >= 633 sets the Khazard commander on the player.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TreeGnomeVillage.java#L949
    TGV_BATTLEFIELD_WALL_CLIMB(everyScenery(
            393,
            p -> bySide(
                    playerYAtLeast(633),
                    (from, south) -> south
                            ? scenery(
                                    393,
                                    from,
                                    to(659, 632),
                                    80,
                                    clickAt(p.at()),
                                    when(questAtLeast(TREE_GNOME_VILLAGE, 5)))
                            : scenery(393, from, to(659, 633), 80, clickAt(p.at())),
                    p.plus(1, -1),
                    p.plus(1, 1)))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap1.java#L100
    UNDERGROUND_PASS_CAVE_ENTRANCE_ENTER(everyScenery(
            725,
            p -> List.of(scenery(
                    725, p.plus(-1, 0), to(673, 3420), 80, clickAt(p.at()), when(questAtLeast(UNDERGROUND_PASS, 2)))))),
    UNDERGROUND_PASS_CRUMBLED_ROCK_CLIMB_OUT(everyScenery(
            728, p -> List.of(scenery(728, p.plus(1, 0), to(713, 581), 80, clickAt(p.at()))))),

    // Only the gate at (689,3513).
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Observatory.java#L534
    OBSERVATORY_KEEP_GATE_N_S(scenery(
            926,
            from(690, 3513),
            to(690, 3514),
            80,
            clickAt(689, 3513),
            when(Prereq.any(cache("keep_key_gate"), questComplete(OBSERVATORY_QUEST))))),
    OBSERVATORY_KEEP_GATE_S_N(scenery(
            926,
            from(690, 3514),
            to(690, 3513),
            80,
            clickAt(689, 3513),
            when(Prereq.any(cache("keep_key_gate"), questComplete(OBSERVATORY_QUEST))))),

    SHEEP_HERDER_DISEASED_PEN_GATE(everyScenery(
            443,
            p -> bySide(
                    playerXAtMost(588),
                    (from, west) -> west
                            ? scenery(
                                    443,
                                    from,
                                    to(589, 541),
                                    80,
                                    clickAt(p.at()),
                                    when(Prereq.all(
                                            wearing(ItemId.PROTECTIVE_JACKET.id()),
                                            wearing(ItemId.PROTECTIVE_TROUSERS.id()))))
                            // At x 589 the gate opens without the suit.
                            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/SheepHerder.java#L215
                            : from.x() == 589
                                    ? scenery(443, from, to(588, 540), 80, clickAt(p.at()))
                                    : scenery(
                                            443,
                                            from,
                                            to(588, 540),
                                            80,
                                            clickAt(p.at()),
                                            when(Prereq.all(
                                                    wearing(ItemId.PROTECTIVE_JACKET.id()),
                                                    wearing(ItemId.PROTECTIVE_TROUSERS.id())))),
                    p.plus(0, 1),
                    p.plus(1, 1)))),

    // The way in needs the gate's ogre guard within 5 tiles.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerGateObstacles.java#L44
    GU_TANOTH_EAST_SOUTH_GATE(everyScenery(
                    988,
                    p -> bySide(
                            playerYAtLeast(794),
                            (from, south) -> south
                                    ? scenery(988, from, to(630, 792), 80, clickAt(p.at()))
                                    : scenery(
                                            988,
                                            from,
                                            to(630, 795),
                                            80,
                                            clickAt(p.at()),
                                            awaitsDialog(),
                                            when(Prereq.any(cache("has_gold_ogre"), questComplete(WATCHTOWER)))),
                            p.plus(1, 0),
                            p.plus(1, 1)))
            .writtenFor(Point.of(630, 793))),
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerGateObstacles.java#L77
    GU_TANOTH_NORTH_WEST_GATE(everyScenery(
                    989,
                    p -> bySide(
                            playerXAtLeast(666),
                            (from, west) -> west
                                    ? scenery(989, from, to(665, 773), 80, clickAt(p.at()))
                                    : scenery(
                                            989,
                                            from,
                                            to(667, 773),
                                            80,
                                            clickAt(p.at()),
                                            awaitsDialog(),
                                            when(Prereq.any(
                                                    cache("has_ogre_companionship"), questComplete(WATCHTOWER)))),
                            p.plus(-1, 1),
                            p.plus(0, 1)))
            .writtenFor(Point.of(666, 772))),

    // Only the gate at x 494.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/barcrawl/AlfredGrimhandBarCrawl.java#L54
    BARBARIAN_OUTPOST_GATE_ENTER(
            scenery(311, from(494, 543), to(493, 543), 80, clickAt(494, 543), when(cache("barcrawl_completed")))),
    BARBARIAN_OUTPOST_GATE_EXIT(
            scenery(311, from(493, 543), to(494, 543), 80, clickAt(494, 543), when(cache("barcrawl_completed")))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L162
    ARDOUGNE_WALL_GATEWAY(everyScenery(
            450,
            p -> List.of(scenery(
                    450,
                    p.at(),
                    p.x() >= 624 ? to(620, 589) : to(626, 588),
                    80,
                    clickAt(p.at()),
                    when(questComplete(BIOHAZARD)))))),

    // Members only.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1309
    VARROCK_BIOHAZARD_GATE_W_E(scenery(513, from(93, 521), to(94, 521), 80)),
    // At Biohazard stages 6 and 7 the guard takes the ethenea, broline and honey vials.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1315
    VARROCK_BIOHAZARD_GATE_E_W(scenery(513, from(94, 521), to(92, 522), 80, clickAt(93, 521))),

    // Opens only for a player at y <= 3715.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestDarkMetalGate.java#L27
    LEGENDS_DARK_METAL_GATE_OPEN_NORTH(scenery(1165, from(474, 3715), to(474, 3720), 80)),

    // A Strength roll: a failed one lowers Strength by one and leaves the player in place.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L371
    HEAVY_METAL_GATE_FORCE_OPEN(everyScenery(
            1033,
            p -> bySide(
                    playerYAtMost(3717),
                    (from, north) -> sceneryMenu(
                            1033,
                            from,
                            north ? to(441, 3719) : to(441, 3717),
                            90,
                            clickAt(p.at()),
                            when(level(Skill.STRENGTH, 50)),
                            answers("force them open"),
                            answers("very strong"),
                            secondCommand()),
                    p.plus(1, -1),
                    p.plus(1, 1)))),

    // From y < 3703 only a picked lock opens them, on a Thieving roll.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L494
    ANCIENT_WOODEN_DOORS(everyScenery(
            1160,
            p -> bySide(
                    playerYAtLeast(3703),
                    (from, south) -> south
                            ? sceneryMenu(1160, from, to(442, 3701), 80, clickAt(p.at()))
                            : sceneryMenu(
                                    1160,
                                    from,
                                    to(441, 3703),
                                    90,
                                    clickAt(p.at()),
                                    when(Prereq.all(level(Skill.THIEVING, 50), carrying(ItemId.LOCKPICK.id(), 1))),
                                    secondCommand()),
                    p.plus(0, 1),
                    p.at()))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGates.java#L23
    LEGENDS_HALL_DOORS_OPEN(everyScenery(
            1080,
            p -> bySide(
                    playerYAtMost(539),
                    (from, north) -> scenery(
                            1080,
                            from,
                            north ? to(513, 541) : to(513, 539),
                            80,
                            clickAt(p.at()),
                            when(questAtLeast(LEGENDS_QUEST, 11))),
                    p.plus(0, -1),
                    p.at()))),

    // The way in runs through the guard's dialogue, which opens the gate at (512,550) whichever
    // placement was clicked.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/npcs/LegendsQuestGuildGuard.java#L208
    WROUGHT_MITHRIL_GATES_OPEN(everyScenery(
                    1079,
                    p -> bySide(
                            playerYAtMost(550),
                            (from, north) -> north
                                    ? scenery(1079, from, to(513, 552), 80, clickAt(p.at()))
                                    : scenery(
                                            1079,
                                            from,
                                            to(513, 549),
                                            90,
                                            clickAt(p.at()),
                                            awaitsDialog(),
                                            when(questAtLeast(LEGENDS_QUEST, 1))),
                            p.at(),
                            p.plus(0, 1)))
            .writtenFor(Point.of(512, 550))),

    // The guards seal the way out at stage 8 and the way in at stage 10. At stage 0 the first
    // click from outside only asks Femi's favour, and the gate opens on the next.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1387
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L1519
    GNOME_STRONGHOLD_GATE(everyScenery(
                    626,
                    p -> List.of(
                            sceneryMenu(
                                    626,
                                    p.at(),
                                    p.plus(0, 1),
                                    80,
                                    clickAt(p.at()),
                                    when(notQuestAt(GRAND_TREE, 8)),
                                    note("the gnome guards seal it at Grand Tree stage 8 — leave by glider")),
                            sceneryMenu(
                                    626,
                                    p.plus(0, 1),
                                    p.at(),
                                    90,
                                    clickAt(p.at()),
                                    when(notQuestAt(GRAND_TREE, 10)),
                                    answers("ok then"),
                                    note("does the gnome Femi a one-time favour on the way in"))))
            .facing(2)
            .writtenFor(Point.of(703, 531))),

    BARBARIAN_AGILITY_COURSE_NET_CLIMB_UP(everyScenery(
            677, p -> List.of(scenery(677, p.plus(-1, 0), to(496, 1507), 60, clickAt(p.at()))))),

    // Needs the cart driver within 10 tiles.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/CartDriver.java#L118
    SHILO_CART_BRIMHAVEN_BOARD_SCENERY(everyScenery(
                    768,
                    p -> List.of(sceneryMenu(
                            768,
                            p.plus(-1, 0),
                            to(468, 662),
                            150,
                            clickAt(p.at()),
                            pays(ItemId.COINS.id(), 500),
                            answers("yes"))))
            .writtenFor(Point.of(419, 853))),

    // Placed only by the extra scenery locs; needs the driver within 10 tiles and Shilo Village done.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L233
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/CartDriver.java#L134
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/CartDriver.java#L58
    BRIMHAVEN_CART_SHILO_BOARD_SCENERY(everyScenery(
                    769,
                    p -> List.of(sceneryMenu(
                            769,
                            p.plus(-1, 0),
                            to(417, 855),
                            150,
                            clickAt(p.at()),
                            pays(ItemId.COINS.id(), 500),
                            when(questComplete(SHILO_VILLAGE)),
                            answers("yes"))))
            .writtenFor(Point.of(465, 663))),

    // Only the cart at (384,851).
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L48
    SHILO_FARM_CART_CLIMB_E_W(scenery(613, from(386, 852), to(383, 852), 45)),

    // From x < 386 the cart is crossed by its search command.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L60
    SHILO_FARM_CART_CLIMB_W_E(
            sceneryMenu(613, from(383, 852), to(386, 852), 45, clickAt(384, 851), answers("nimble"), secondCommand())),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L126
    ENTRANA_SHIP_PORT_SARIM_241(everyScenery(
            241, p -> List.of(scenery(241, p.plus(0, -1), to(263, 660), 80, clickAt(p.at()))))),
    ENTRANA_SHIP_PORT_SARIM_242(everyScenery(
            242, p -> List.of(scenery(242, p.plus(0, -1), to(263, 660), 80, clickAt(p.at()))))),
    ENTRANA_SHIP_PORT_SARIM_243(everyScenery(
            243, p -> List.of(scenery(243, p.plus(0, -1), to(263, 660), 80, clickAt(p.at()))))),

    HAZEEL_CAVE_ENTRANCE_ENTER(everyScenery(
            417, p -> List.of(scenery(417, p.plus(-1, 0), to(617, 3479), 80, clickAt(p.at()))))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/MagicalPool.java#L19
    MAGE_ARENA_MAGICAL_POOL_DESCEND_TO_GOD_SPELL_CHAMBER(everyScenery(
            1155,
            p -> List.of(scenery(
                    1155, p.plus(-1, 0), to(471, 3385), 80, clickAt(p.at()), when(cacheAtLeast("mage_arena", 2)))))),
    MAGE_ARENA_MAGICAL_POOL_RETURN_TO_KOLODIONS_CAVE(everyScenery(
            1166, p -> List.of(scenery(1166, p.plus(-1, 0), to(447, 3373), 80, clickAt(p.at()))))),

    // Once the quest is complete both winches lower to one cavern.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/digsite/DigsiteWinch.java#L32
    DIG_SITE_WINCH_OPERATE_WEST_SHAFT(everyScenery(
            1095,
            p -> List.of(
                    scenery(1095, p.plus(0, 2), to(19, 3385), 80, clickAt(p.at()), when(questComplete(DIGSITE)))))),
    DIG_SITE_WINCH_OPERATE_EAST_SHAFT(everyScenery(
            1053,
            p -> List.of(
                    scenery(1053, p.plus(-1, 0), to(19, 3385), 80, clickAt(p.at()), when(questComplete(DIGSITE)))))),

    DIG_SITE_WINCH_ROPE_CLIMB_DOWN_WEST_SHAFT(everyScenery(
            1095,
            p -> List.of(scenery(
                    1095,
                    p.plus(0, 2),
                    to(26, 3346),
                    80,
                    clickAt(p.at()),
                    when(Prereq.all(
                            notQuestComplete(DIGSITE),
                            cache("digsite_winshaft"),
                            cache("winch_rope_1"),
                            level(Skill.AGILITY, 10))))))),
    DIG_SITE_WINCH_ROPE_CLIMB_DOWN_EAST_SHAFT_BEFORE_STAGE_6(everyScenery(
            1053,
            p -> List.of(scenery(
                    1053,
                    p.plus(-1, 0),
                    to(19, 3337),
                    80,
                    clickAt(p.at()),
                    when(Prereq.all(
                            notQuestAtLeast(DIGSITE, 6),
                            cache("digsite_winshaft"),
                            cache("winch_rope_2"),
                            level(Skill.AGILITY, 10))))))),
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/digsite/DigsiteWinch.java#L81
    DIG_SITE_WINCH_ROPE_CLIMB_DOWN_EAST_SHAFT_STAGE_6(everyScenery(
            1053,
            p -> List.of(scenery(
                    1053,
                    p.plus(-1, 0),
                    to(19, 3385),
                    80,
                    clickAt(p.at()),
                    when(Prereq.all(
                            questAt(DIGSITE, 6),
                            cache("digsite_winshaft"),
                            cache("winch_rope_2"),
                            level(Skill.AGILITY, 10))))))),

    // The forester takes the toll before checking Woodcutting 55, and keeps it from a lower level.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/WoodcuttingGuild.java#L40
    MCGRUBORS_WOOD_FORESTER_TOLL(onlyIn(
            feature(WorldProfile.Feature.WOODCUTTING_GUILD),
            sceneryMenu(
                    356,
                    from(560, 473),
                    to(560, 472),
                    150,
                    clickAt(560, 472),
                    pays(ItemId.COINS.id(), 1000),
                    when(level(Skill.WOODCUTTING, 55)),
                    answers("here you go"),
                    note("the forester must be near the gate")))),

    // From x <= 564 Morris must be within 15 tiles.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/FishingContest.java#L529
    FISHING_CONTEST_GATE(everyScenery(
                    SceneryId.GATE_WOODEN_FISHING_CONTEST_CLOSED.id(),
                    p -> bySide(
                            playerXAtMost(564),
                            (from, morris) -> morris
                                    ? scenery(
                                            SceneryId.GATE_WOODEN_FISHING_CONTEST_CLOSED.id(),
                                            from,
                                            p.doGateLanding(from),
                                            10,
                                            clickAt(p.at()),
                                            awaitsDialog(),
                                            when(carrying(ItemId.FISHING_COMPETITION_PASS.id(), 1)))
                                    : sceneryMenu(
                                            SceneryId.GATE_WOODEN_FISHING_CONTEST_CLOSED.id(),
                                            from,
                                            p.doGateLanding(from),
                                            10,
                                            clickAt(p.at()),
                                            answers("compete again another day")),
                            p.at(),
                            p.plus(1, 0)))
            .facing(4)
            .writtenFor(Point.of(564, 492))),

    // The gates are shut only while a lever holds them. A shut one offers the climb only with
    // want_fixed_broken_mechanics, from the tile at the gate's x - 1, and lands on the gate's tile
    // even when the roll fails.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/ClockTower.java#L232
    CLOCK_TOWER_RAT_CAGE_INNER_GATE_CLIMB_W_E(sceneryMenu(
            SceneryId.GATE_METAL_QUEST_CLOSED.id(),
            from(589, 3475),
            to(590, 3475),
            20,
            clickAt(590, 3475),
            answers("yes, climb the gate"))),
    CLOCK_TOWER_RAT_CAGE_OUTER_GATE_CLIMB_W_E(sceneryMenu(
            SceneryId.GATE_METAL_QUEST_CLOSED.id(),
            from(593, 3475),
            to(594, 3475),
            20,
            clickAt(594, 3475),
            answers("yes, climb the gate"))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/FishingContest.java#L541
    FISHING_CONTEST_GATE_ENTER_FISH_AROUND_AFTER_QUEST(onlyIn(
            rule(ServerRules::postQuestRegionsOpen),
            everyScenery(
                            SceneryId.GATE_WOODEN_FISHING_CONTEST_CLOSED.id(),
                            p -> List.of(sceneryMenu(
                                    SceneryId.GATE_WOODEN_FISHING_CONTEST_CLOSED.id(),
                                    p.at(),
                                    p.plus(1, 0),
                                    10,
                                    clickAt(p.at()),
                                    answers("fish around"),
                                    when(questComplete(FISHING_CONTEST)))))
                    .facing(4)
                    .writtenFor(Point.of(564, 492)))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L143
    LUMBRIDGE_ROWBOAT_EDGEVILLE(everyScenery(
            1242, p -> List.of(scenery(1242, p.plus(0, 2), to(206, 449), 45, clickAt(p.at()))))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L131
    SCOT_RUTH_TUNNEL_CHAOS_ALTAR(everyScenery(
            1241,
            p -> List.of(scenery(
                    1241,
                    p.plus(-1, 0),
                    to(331, 213),
                    45,
                    clickAt(p.at()),
                    when(cache(ScotRuthTunnelTransport.KEY)))))),
    ;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    SceneryCrossings(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (SceneryCrossings c : values()) {
            out.addAll(c.expand(env));
        }
        return out;
    }
}
