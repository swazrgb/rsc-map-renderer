package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Door.door;
import static openrsc.nav.transports.spec.Door.everyDoor;
import static openrsc.nav.transports.spec.Door.everyDoorBySide;
import static openrsc.nav.transports.spec.Door.everySceneryDoor;
import static openrsc.nav.transports.spec.Door.everySceneryDoorBySide;
import static openrsc.nav.transports.spec.Door.sceneryDoor;
import static openrsc.nav.transports.spec.EveryPlacement.everyBoundary;
import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.byWorld;
import static openrsc.nav.transports.spec.Rows.highToLowCost;
import static openrsc.nav.transports.spec.Rows.highToLowLandsAt;
import static openrsc.nav.transports.spec.Rows.lowToHighLandsAt;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.openedWith;
import static openrsc.nav.transports.spec.Rows.pays;
import static openrsc.nav.transports.spec.WorldGate.feature;
import static openrsc.nav.transports.spec.WorldGate.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.TransportRef;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.Door;
import openrsc.nav.transports.spec.EveryPlacement;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Doors and gates that open only for some accounts or only from one side: guild doors, quest and
 * key doors, picked locks, one-way exits. A door anyone may open is not here: the collision map
 * leaves it open ({@link ServerData#isGenericFreeDoor}).
 */
public enum SpecialDoor implements TransportDef {
    /**
     * A worn cooking cape stands in for the chef's hat, on a world that has the cape.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L720">DoorAction.onWallObjectAction</a>
     */
    DOOR_COOKING_GUILD(byWorld(
            item(ItemId.COOKING_CAPE.id()),
            door(43, 179, 488, 0, all(skill(Skill.COOKING, 32), Kit.CHEF_GEAR)),
            door(43, 179, 488, 0, all(skill(Skill.COOKING, 32), Kit.CHEFS_HAT)))),
    DOOR_CHAMPIONS_GUILD(door(44, 150, 554, 0, questPts(32))),

    /**
     * Only entering, from y &lt; 3381, asks for the level.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L669">DoorAction.onWallObjectAction</a>
     */
    DOOR_MINING_GUILD(door(55, 268, 3381, 0, skill(Skill.MINING, 60), Prereq.ALWAYS)),
    /**
     * A worn crafting cape stands in for the brown apron, on a world that has the cape.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L697">DoorAction.onWallObjectAction</a>
     */
    DOOR_CRAFTING_GUILD(byWorld(
            item(ItemId.CRAFTING_CAPE.id()),
            door(68, 347, 601, 0, all(skill(Skill.CRAFTING, 40), Kit.CRAFTING_GEAR)),
            door(68, 347, 601, 0, all(skill(Skill.CRAFTING, 40), Kit.BROWN_APRON)))),
    DOOR_FISHING_GUILD(door(112, 586, 524, 0, skill(Skill.FISHING, 68))),
    DOOR_WIZARDS_GUILD(door(146, 599, 757, 1, skill(Skill.MAGIC, 66))),

    /**
     * From x &lt;= 198 the bronze key opens it, at stage 2 and only while Lady Keli is out of sight; from
     * the other side a click opens it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/PrinceAliRescue.java#L988">PrinceAliRescue.onUseBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/PrinceAliRescue.java#L1043">PrinceAliRescue.onOpBound</a>
     */
    DOOR_PRINCE_ALI_JAIL(door(
            45,
            199,
            640,
            1,
            all(carrying(ItemId.BRONZE_KEY.id()), questAt(Quest.PRINCE_ALI_RESCUE, 2)),
            Prereq.ALWAYS,
            openedWith(ItemId.BRONZE_KEY.id()),
            note("the planner ignores Lady Keli, who must be lured away first"))),
    DOOR_HEROS_GUILD(everyDoor(74, questDone(Quest.HEROES_QUEST))),
    DOOR_LUCIEN_HIDEOUT(everyDoor(109, questStage(Quest.TEMPLE_OF_IKOV, 1))),
    /**
     * Free from inside the chamber of fear (y 3335 to 3341).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L427">DoorAction.onWallObjectAction</a>
     */
    DOOR_TEMPLE_IKOV_FEAR_ROOM(everyDoorBySide(
            104,
            (p, from) -> from.y() >= 3335 && from.y() <= 3341,
            Prereq.ALWAYS,
            wearing(ItemId.PENDANT_OF_LUCIEN.id()))),
    /**
     * Without the boots the bridge gives way under the player.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L441">DoorAction.onWallObjectAction</a>
     */
    DOOR_TEMPLE_IKOV_BRIDGE(everyDoorBySide(
            105, (p, from) -> from.x() >= 546, Prereq.ALWAYS, wearing(ItemId.BOOTS_OF_LIGHTFOOTEDNESS.id()))),
    DOOR_TEMPLE_IKOV_ICE_ARROW(everyDoorBySide(
            106,
            (p, from) -> from.x() >= 536,
            Prereq.ALWAYS,
            any(questDone(Quest.TEMPLE_OF_IKOV), cacheOn("openSpiderDoor")))),
    DOOR_TEMPLE_IKOV_LEVER_PULL(everyDoor(107, any(questDone(Quest.TEMPLE_OF_IKOV), cacheOn("completeLever")))),
    DOOR_TEMPLE_IKOV_FIRE(everyDoor(108, any(questDone(Quest.TEMPLE_OF_IKOV), cacheOn("killedLesarkus")))),
    DOOR_TEMPLE_IKOV_SHINY_KEY(everyDoor(110, carrying(ItemId.SHINY_KEY.id()), openedWith(ItemId.SHINY_KEY.id()))),

    /**
     * Ernest the Chicken's handler takes it, and opens it only from y &gt;= 553.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ErnestTheChicken.java#L547">ErnestTheChicken.onOpBound</a>
     */
    DOOR_DRAYNOR_MANOR_LOBBY(everyDoorBySide(
            36,
            (p, from) -> from.y() >= 553,
            Prereq.ALWAYS,
            Prereq.NEVER,
            note("never opens from inside; leave by the back door"))),

    /**
     * From x &lt;= 270 the guards' uniform must be worn: iron chain mail (the body or the top) and a
     * medium bronze helmet. From inside it opens free.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/BlackKnightsFortress.java#L289">BlackKnightsFortress.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/BlackKnightsFortress.java#L327">BlackKnightsFortress.onOpBound</a>
     */
    DOOR_BLACK_KNIGHT_GUARD(door(
            38,
            271,
            441,
            1,
            all(
                    any(wearing(ItemId.IRON_CHAIN_MAIL_BODY.id()), wearing(ItemId.IRON_CHAIN_MAIL_TOP.id())),
                    wearing(ItemId.MEDIUM_BRONZE_HELMET.id())),
            Prereq.ALWAYS)),

    /**
     * From y &lt;= 442 the first click sets a Black Knight within 5 tiles on the player; the next goes
     * through.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/BlackKnightsFortress.java#L361">BlackKnightsFortress.onOpBound</a>
     */
    DOOR_BLACK_KNIGHT_ESCAPE(door(
            40, 278, 443, 0, Prereq.ALWAYS, note("the planner ignores the Black Knights, who stop you while near"))),

    /**
     * Wydin hires only a player wearing a white apron, and lets one through only while it is worn.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/WydinsGrocery.java#L129">WydinsGrocery.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/WydinsGrocery.java#L145">WydinsGrocery.onOpBound</a>
     */
    DOOR_WYDIN_GROCERY(door(47, 277, 658, 1, all(cacheOn("job_wydin"), wearing(ItemId.WHITE_APRON.id())))),
    DOOR_WITCHS_MAIN_ROOM(door(70, 358, 495, 1, Prereq.ALWAYS)),

    /**
     * Opens for a Phoenix Gang member ({@link #ARRAV_GANG} {@link #ARRAV_PHOENIX}, or the quest done
     * as one) past stage 4. The handler moves a gang member one tile further after the door, so each
     * arm lands two tiles from where it started.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ShieldOfArrav.java#L545-L587">ShieldOfArrav.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ShieldOfArrav.java#L85">ShieldOfArrav.isPhoenixGang</a>
     */
    DOOR_PHOENIX_HQ(door(
            19,
            110,
            3370,
            0,
            all(
                    questStage(Quest.SHIELD_OF_ARRAV, 5),
                    any(
                            cacheIs(SpecialDoor.ARRAV_GANG, SpecialDoor.ARRAV_PHOENIX),
                            questAt(Quest.SHIELD_OF_ARRAV, SpecialDoor.ARRAV_DONE_PHOENIX))),
            lowToHighLandsAt(110, 3371),
            highToLowLandsAt(110, 3368))),
    /**
     * Opens for a Black Arm member ({@link #ARRAV_GANG} {@link #ARRAV_BLACK_ARM}, or the quest done
     * as one) past stage 4.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ShieldOfArrav.java#L531-L544">ShieldOfArrav.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ShieldOfArrav.java#L80">ShieldOfArrav.isBlackArmGang</a>
     */
    DOOR_BLACKARM_HQ(door(
            21,
            148,
            533,
            0,
            all(
                    questStage(Quest.SHIELD_OF_ARRAV, 5),
                    any(
                            cacheIs(SpecialDoor.ARRAV_GANG, SpecialDoor.ARRAV_BLACK_ARM),
                            questAt(Quest.SHIELD_OF_ARRAV, SpecialDoor.ARRAV_DONE_BLACK_ARM))))),

    DOOR_DRAYNOR_MANOR_BACK(door(37, 199, 551, 0, Prereq.ALWAYS)),
    /**
     * Without the Khazard helmet and chainmail worn, only the outer side of the doors on x 603 and on
     * y 699 is stopped; every other side opens.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L474">DoorAction.onWallObjectAction</a>
     */
    DOOR_KHAZARD_HOUSE(everyDoorBySide(
            113,
            (p, from) -> (p.x() == 603 && from.x() < p.x()) || (p.z() == 699 && from.y() < p.z()),
            all(wearing(ItemId.KHAZARD_HELMET.id()), wearing(ItemId.KHAZARD_CHAINMAIL.id())),
            Prereq.ALWAYS)),
    DOOR_KHAZARD_BFIELD_LOBBY(everyDoor(114, any(questStage(Quest.FIGHT_ARENA, 3), cacheOn("freed_servil")))),
    DOOR_KHAZARD_BATTLE_FIELD(everyDoor(115, Prereq.ALWAYS)),

    DOOR_MAGIC_WHISTLE(everyDoor(116, Prereq.ALWAYS)),
    DOOR_MERLIN_LOCKED(everyDoor(
            117,
            any(
                    questDone(Quest.THE_HOLY_GRAIL),
                    all(questStage(Quest.THE_HOLY_GRAIL, 1), questDone(Quest.MERLINS_CRYSTAL))))),
    DOOR_TO_BRAVEK(everyDoorBySide(121, (p, from) -> from.x() >= 648, questStage(Quest.PLAGUE_CITY, 9), Prereq.ALWAYS)),

    DOOR_REHNISON_FAMILY(everyDoor(122, questStage(Quest.PLAGUE_CITY, 6))),

    /**
     * From outside (y &lt;= 605 or y &gt;= 612) a mourner turns the player away unless a warrant is
     * carried; from inside it opens free.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L563">DoorAction.onWallObjectAction</a>
     */
    DOOR_INFECTED_ELENA(everyDoorBySide(
                    123,
                    (p, from) -> from.y() <= 605 || from.y() >= 612,
                    any(questStage(Quest.PLAGUE_CITY, 11), carrying(ItemId.WARRANT.id())),
                    Prereq.ALWAYS)
            .writtenFor(Point.of(635, 612), Point.of(637, 606))),

    DOOR_ELENA_HOUSE(everyDoor(152, questDone(Quest.PLAGUE_CITY))),

    DOOR_MOURNER_HEADQUARTERS(everyDoor(
            138,
            all(wearing(ItemId.DOCTORS_GOWN.id()), any(cacheOn("rotten_apples"), questStage(Quest.BIOHAZARD, 5))))),

    DOOR_SICK_MOURNER(
            everyDoorBySide(141, (p, from) -> from.y() >= 1513, Prereq.ALWAYS, wearing(ItemId.DOCTORS_GOWN.id()))),

    DOOR_GUIDOR_ROOM(everyDoorBySide(
            145,
            (p, from) -> from.x() <= 82,
            questStage(Quest.BIOHAZARD, 7),
            all(
                    questStage(Quest.BIOHAZARD, 7),
                    all(wearing(ItemId.PRIEST_ROBE.id()), wearing(ItemId.PRIEST_GOWN.id()))))),
    DOOR_TRAINING_OGRES(everyDoor(150, Prereq.ALWAYS)),

    /**
     * Opens only from x &gt;= 407. The handler tests the stage as {@code >= 8}, which a completed
     * quest's -1 fails, so the door stays shut once the quest is done.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L194">DoorAction.onWallObjectAction</a>
     */
    DOOR_SHIPYARD_FOREMAN(everyDoorBySide(
            161,
            (p, from) -> from.x() >= 407,
            all(questStage(Quest.GRAND_TREE, 8), Prereq.notFact(Fact.questComplete(QuestId.GRAND_TREE.id()))),
            Prereq.NEVER)),

    /**
     * Ernest the Chicken's handler takes every door 32 by id, so this one, outside the basement, opens
     * on the basement's levers too.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ErnestTheChicken.java#L644">ErnestTheChicken.onOpBound</a>
     */
    DOOR_ERNEST_RIGHT_BOTTOM(door(
            32,
            279,
            1383,
            0,
            all(
                    cacheOn("LeverA"),
                    cacheOn("LeverB"),
                    cacheOff("LeverC"),
                    cacheOff("LeverD"),
                    cacheOff("LeverE"),
                    cacheOff("LeverF")))),

    /**
     * Locked, unless the world has {@code want_barter_wormbrains} on: then the click starts
     * Wormbrain's dialogue instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L787">DoorAction.onWallObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/WormBrain.java#L21">WormBrain.blockOpBound</a>
     */
    DOOR_WORMBRAIN_CELL(door(
            30,
            283,
            665,
            0,
            Prereq.NEVER,
            note("opens only through the WormBrain dialogue, which the planner does not drive"))),

    DOOR_ARDY_DUNG_SE(door(88, 509, 3441, 0, all(cacheOn("north_leverA"), cacheOn("south_lever")))),
    DOOR_ARDY_DUNG_SW(door(90, 512, 3441, 0, all(cacheOn("north_leverA"), cacheOff("south_lever")))),
    DOOR_PERFECT_GOLD(everyDoor(
            91,
            any(
                    questDone(Quest.FAMILY_CREST),
                    all(cacheOn("north_leverA"), cacheOn("north_leverB"), cacheOff("south_lever"))))),
    DOOR_ARDY_DUNG_N(
            everyDoor(92, all(cacheOff("north_leverA"), any(cacheOn("south_lever"), cacheOn("north_leverB"))))),

    DOOR_WITCHS_RAT_MAGNET(door(71, 356, 495, 1, questStage(Quest.WITCHES_HOUSE, 2))),

    DOOR_WITCHS_HIDEOUT(door(72, 356, 492, 1, Prereq.ALWAYS)),

    /**
     * Opens once the witch has left it unlocked, at stage 3; at stage 2 a click calls her out.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/WitchesHouse.java#L186">WitchesHouse.onOpBound</a>
     */
    DOOR_WITCHS_SHED(door(73, 351, 492, 0, questStage(Quest.WITCHES_HOUSE, 3))),

    DOOR_GARV_GUARD(door(75, 463, 681, 0, any(questDone(Quest.HEROES_QUEST), cacheOn("garv_door")))),
    DOOR_GRUBOR_CODE(door(76, 439, 694, 0, all(cacheOn("blackarm_mission"), cacheOn("talked_grubor")))),
    DOOR_GRIP_ROOM(door(77, 463, 676, 0, any(questDone(Quest.HEROES_QUEST), cacheOn("talked_grip")))),
    DOOR_CHARLIE_COOK(door(78, 448, 682, 0, any(questDone(Quest.HEROES_QUEST), cacheOn("talked_alf")))),

    STRANGE_PANEL_COOK(everyDoor(79, Prereq.ALWAYS)),

    /**
     * The four items are taken as the player passes from x &gt;= 259.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L430">DragonSlayer.onOpBound</a>
     */
    DOOR_MAP_PIECE_DWARVEN(everyDoorBySide(
            57,
            (p, from) -> from.x() <= 258,
            Prereq.ALWAYS,
            all(
                    carrying(ItemId.WIZARDS_MIND_BOMB.id()),
                    carrying(ItemId.SILK.id()),
                    carrying(ItemId.LOBSTER_POT.id()),
                    carrying(ItemId.UNFIRED_BOWL.id())),
            pays(ItemId.WIZARDS_MIND_BOMB.id(), 1),
            pays(ItemId.SILK.id(), 1),
            pays(ItemId.LOBSTER_POT.id(), 1),
            pays(ItemId.UNFIRED_BOWL.id(), 1))),

    /**
     * Going through from Crandor's side sets {@code crandor_shortcut}, which opens it from the other
     * side.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L444">DragonSlayer.onOpBound</a>
     */
    DOOR_CRANDOR_KARAMJA(everyDoorBySide(
            58,
            (p, from) -> from.y() <= 3517,
            Prereq.ALWAYS,
            any(questDone(Quest.DRAGON_SLAYER), cacheOn("crandor_shortcut")),
            note("Dragon Slayer done stands in for the unobservable crandor_shortcut flag"))),

    DOOR_MELZAR_BASEMENT(
            everyDoorBySide(54, (p, from) -> from.x() == p.x() - 1, Prereq.ALWAYS, Prereq.NEVER, note("exit-only"))),

    /**
     * From x &gt;= 374, while the suit of armour stands in its alcove, a click brings it to life
     * instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/DruidicRitual.java#L325">DruidicRitual.onOpBound</a>
     */
    DOOR_DRUIDIC_GUARDED(door(
            63,
            374,
            3332,
            1,
            Prereq.ALWAYS,
            highToLowCost(1_000_000),
            note("the planner ignores the suit of armour, which attacks on approach"))),
    DOOR_DRUIDIC_WALK_A(door(64, 367, 3332, 1, Prereq.ALWAYS)),
    DOOR_DRUIDIC_WALK_B(door(64, 369, 3336, 0, Prereq.ALWAYS)),

    UNUSUAL_WALL_SCORPION(door(87, 383, 3353, 0, questAt(Quest.SCORPION_CATCHER, 2))),
    RAT_CAGE_CLOCK_TOWER(everyBoundary(
            111,
            p -> landingBySide(
                    p,
                    any(questDone(Quest.CLOCK_TOWER), cacheOn("rats_dead")),
                    from -> from.x() >= 583 ? Point.of(582, 3476) : Point.of(583, 3476)))),

    /**
     * Without the cat delivered, Kardia blasts the player for current Hits / 5 + 5 instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/npcs/UndergroundPassKardiaTheWitch.java#L58">UndergroundPassKardiaTheWitch.onOpBound</a>
     */
    DOOR_KARDIAS_PLACE(everyDoor(173, cacheOn("kardia_cat"))),

    /**
     * The jail flag cannot be observed, so this arm may be taken while jailed; the answers then refuse
     * Shantay's fine and take his free transfer to the Port Sarim jail.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L272">DoorAction.onWallObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L957">DoorAction.sendToPortSarim</a>
     */
    JAIL_DOOR_SHANTAY_PASS(
            everyDoor(176, cacheOff("shantay_jail"), answers("not having my money"), answers("do your worst"))),

    JAIL_DOOR_MINING_CAVE(everyBoundary(
            180,
            p -> landingBySide(
                    p,
                    (q, from) -> from.x() >= 72,
                    carryingCount(ItemId.ROCKS.id(), 15),
                    Prereq.NEVER,
                    from -> Point.of(69, 3625),
                    pays(ItemId.ROCKS.id(), 15),
                    note("the jail mercenary must be at the gate")))),

    TENT_DOOR_ANVIL(everyBoundary(
            198,
            p -> landingBySide(
                    p, (q, from) -> from.y() <= 793, Prereq.ALWAYS, Prereq.NEVER, from -> Point.of(171, 795)))),

    STURDY_IRON_GATE(everyBoundary(
            200,
            p -> landingBySide(
                    p,
                    (q, from) -> from.y() >= 3617,
                    carrying(ItemId.WROUGHT_IRON_KEY.id()),
                    Prereq.ALWAYS,
                    from -> from.y() >= 3617 ? Point.of(from.x(), from.y() - 1) : Point.of(from.x(), from.y() + 1)))),

    DOOR_FIX_DWARF_CANNON(everyDoor(194, questAt(Quest.DWARF_CANNON, 4))),
    DOOR_DWARF_CANNON_ENGINEER(door(197, 278, 493, 1, questStage(Quest.DWARF_CANNON, 5))),

    LOW_FENCE_LUMBER_MILL(door(199, 51, 438, 1, questStage(Quest.GERTRUDES_CAT, 2))),

    /**
     * Clock Tower claims the wall at (584,3457) by its tile and opens it the same way.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/ClockTower.java#L381">ClockTower.onOpBound</a>
     */
    ODD_LOOKING_WALL(everyDoor(22, Prereq.ALWAYS)),

    DOOR_BRASS_KEY(everyDoor(23, carrying(ItemId.BRASS_KEY.id()), openedWith(ItemId.BRASS_KEY.id()))),
    DOOR_DRAYNOR_CLOSET_KEY(everyDoor(35, carrying(ItemId.CLOSET_KEY.id()), openedWith(ItemId.CLOSET_KEY.id()))),

    DOOR_WITCHS_FRONT_KEY(everyDoor(
            69,
            all(carrying(ItemId.FRONT_DOOR_KEY.id()), questStage(Quest.WITCHES_HOUSE, 1)),
            openedWith(ItemId.FRONT_DOOR_KEY.id()))),
    DOOR_PHOENIX_WEAPON_KEY(door(
            20,
            103,
            532,
            0,
            carrying(ItemId.PHOENIX_GANG_WEAPON_KEY.id()),
            openedWith(ItemId.PHOENIX_GANG_WEAPON_KEY.id()))),
    DOOR_WATERFALL_OLD_KEY(everyDoor(135, carrying(ItemId.AN_OLD_KEY.id()), openedWith(ItemId.AN_OLD_KEY.id()))),
    DOOR_MELZAR_MAZE_KEY(everyDoor(60, carrying(ItemId.MAZE_KEY.id()), openedWith(ItemId.MAZE_KEY.id()))),
    DOOR_HEROS_MISC_KEY(
            everyDoor(80, carrying(ItemId.MISCELLANEOUS_KEY.id()), openedWith(ItemId.MISCELLANEOUS_KEY.id()))),
    DOOR_GRIP_TREASURE_KEY(everyDoor(81, carrying(ItemId.BUNCH_OF_KEYS.id()), openedWith(ItemId.BUNCH_OF_KEYS.id()))),
    /**
     * Opens on a plain click while the key is carried.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L3238">TouristTrap.onOpBound</a>
     */
    DOOR_TT_CELL_DOOR_KEY(door(177, 88, 801, 1, carrying(ItemId.CELL_DOOR_KEY.id()))),

    /**
     * Opening it carrying Ana in a barrel loses her to the guards and sends the player to jail.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L2659">TouristTrap.onOpLoc</a>
     */
    TOURIST_TRAP_IRON_GATE(
            everySceneryDoor(932, all(carrying(ItemId.METAL_KEY.id()), notCarrying(ItemId.ANA_IN_A_BARREL.id())))),

    TENT_DOOR_AL_SHABIM(everyDoor(196, Prereq.ALWAYS)),

    DOOR_DUSTY_KEY(door(84, 355, 3353, 1, carrying(ItemId.DUSTY_KEY.id()), openedWith(ItemId.DUSTY_KEY.id()))),

    /**
     * Scorpion Catcher takes the jail keys only on the doors at y 3428 and y 3425.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/ScorpionCatcher.java#L569">ScorpionCatcher.blockUseBound</a>
     */
    DOOR_VELRAK_CELL(everyBoundary(
            83,
            p -> p.z() == 3428 || p.z() == 3425
                    ? List.of(door(
                            83,
                            p.x(),
                            p.z(),
                            p.dir(),
                            carrying(ItemId.JAIL_KEYS.id()),
                            openedWith(ItemId.JAIL_KEYS.id())))
                    : List.of())),

    // Each colour key is used up at its door, either way through:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1098
    DOOR_MELZAR_RED_KEY(everyDoor(
            48, carrying(ItemId.RED_KEY.id()), openedWith(ItemId.RED_KEY.id()), pays(ItemId.RED_KEY.id(), 1))),
    DOOR_MELZAR_ORANGE_KEY(everyDoor(
            49, carrying(ItemId.ORANGE_KEY.id()), openedWith(ItemId.ORANGE_KEY.id()), pays(ItemId.ORANGE_KEY.id(), 1))),
    DOOR_MELZAR_YELLOW_KEY(everyDoor(
            50, carrying(ItemId.YELLOW_KEY.id()), openedWith(ItemId.YELLOW_KEY.id()), pays(ItemId.YELLOW_KEY.id(), 1))),
    DOOR_MELZAR_BLUE_KEY(everyDoor(
            51, carrying(ItemId.BLUE_KEY.id()), openedWith(ItemId.BLUE_KEY.id()), pays(ItemId.BLUE_KEY.id(), 1))),
    DOOR_MELZAR_BLACK_KEY(everyDoor(
            52, carrying(ItemId.BLACK_KEY.id()), openedWith(ItemId.BLACK_KEY.id()), pays(ItemId.BLACK_KEY.id(), 1))),
    DOOR_MELZAR_MAGENTA_KEY(everyDoor(
            53,
            carrying(ItemId.MAGENTA_KEY.id()),
            openedWith(ItemId.MAGENTA_KEY.id()),
            pays(ItemId.MAGENTA_KEY.id(), 1))),

    // A lock-picked door lets a click through only from the side its handler names; from anywhere
    // else the lock is picked, a Thieving roll:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/thieving/Thieving.java#L640
    DOOR_THIEVE_50GP(everyDoorBySide(93, (p, from) -> from.y() <= 591, Prereq.ALWAYS, skill(Skill.THIEVING, 7))),

    /**
     * The four doors the handler lists by tile ask Thieving 16 and let one side through on a click;
     * any other door 94 asks 1 and is picked from both sides.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/thieving/Thieving.java#L578">Thieving.handlePicklock</a>
     */
    DOOR_THIEVE_PICKLOCK(everyBoundary(94, SpecialDoor::picklock94)),

    DOOR_ARDOUGNE_SEWER(everyDoorBySide(95, (p, from) -> from.x() <= 556, Prereq.ALWAYS, skill(Skill.THIEVING, 31))),

    DOOR_CHAOS_DRUID_TOWER(everyDoorBySide(96, (p, from) -> from.y() <= 555, Prereq.ALWAYS, skill(Skill.THIEVING, 46))),

    DOOR_PALADIN_CASTLE(everyDoorBySide(
            97, (p, from) -> from.y() >= 1548 && from.x() == 609, Prereq.ALWAYS, skill(Skill.THIEVING, 61))),
    DOOR_PIRATE_HIDEOUT(everyDoorBySide(
            99,
            (p, from) ->
                    (from.x() >= 263 && from.x() <= 269 && from.y() == 104) || (from.x() == 266 && from.y() >= 100),
            Prereq.ALWAYS,
            all(skill(Skill.THIEVING, 39), carrying(ItemId.LOCKPICK.id())))),
    DOOR_ANIMATED_AXE(everyDoorBySide(
            100,
            (p, from) -> from.y() >= 103 && from.y() <= 107,
            Prereq.ALWAYS,
            all(skill(Skill.THIEVING, 32), carrying(ItemId.LOCKPICK.id())))),
    DOOR_YANILLE_SHORTCUT(everyDoor(162, all(skill(Skill.THIEVING, 82), carrying(ItemId.LOCKPICK.id())))),

    /**
     * The jump moves the player one tile along x, to the wall's x - 1 from its own x and to x + 1
     * otherwise, so only a wall facing dir 1 is a crossing.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/BarbarianAgilityCourse.java#L163">BarbarianAgilityCourse.onOpBound</a>
     */
    LOW_WALL_BARB_LEFT(everyBoundary(
            163, p -> p.dir() == 1 ? List.of(door(163, p.x(), p.z(), p.dir(), Prereq.ALWAYS)) : List.of())),
    LOW_WALL_BARB_RIGHT(everyBoundary(
            164, p -> p.dir() == 1 ? List.of(door(164, p.x(), p.z(), p.dir(), Prereq.ALWAYS)) : List.of())),
    /**
     * Tree Gnome Village takes only the fences on y 705 and the one at (540,445).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TreeGnomeVillage.java#L850">TreeGnomeVillage.blockOpBound</a>
     */
    LOOSE_FENCE_TGV(everyBoundary(
            101,
            p -> p.z() == 705 || (p.x() == 540 && p.z() == 445)
                    ? List.of(door(101, p.x(), p.z(), p.dir(), Prereq.ALWAYS))
                    : List.of())),

    // Each tutorial door asks for its guide's tutorial stage, at its own tile only:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L73
    TUT_DOOR_START_GUIDE(door(125, 222, 743, 1, cacheAtLeast("tutorial", 10))),
    TUT_DOOR_CONTROLS_GUIDE(door(143, 224, 737, 0, cacheAtLeast("tutorial", 15))),
    TUT_DOOR_COMBAT_INSTRUCTOR(door(130, 220, 727, 1, cacheAtLeast("tutorial", 25))),
    TUT_DOOR_COOKING_INSTRUCTOR(door(129, 212, 729, 1, cacheAtLeast("tutorial", 35))),
    TUT_DOOR_FINANCIAL_ADVISOR(door(134, 206, 730, 1, cacheAtLeast("tutorial", 40))),
    TUT_DOOR_FISHING_INSTRUCTOR(door(131, 201, 734, 0, cacheAtLeast("tutorial", 45))),
    TUT_DOOR_MINING_INSTRUCTOR(door(132, 198, 746, 0, cacheAtLeast("tutorial", 55))),
    TUT_DOOR_BANK_ASSISTANT(door(133, 204, 752, 1, cacheAtLeast("tutorial", 60))),
    TUT_DOOR_QUEST_ADVISOR(door(136, 209, 754, 0, cacheAtLeast("tutorial", 65))),
    TUT_DOOR_WILDERNESS_GUIDE(door(139, 217, 760, 1, cacheAtLeast("tutorial", 70))),
    TUT_DOOR_MAGIC_INSTRUCTOR(door(140, 222, 760, 1, cacheAtLeast("tutorial", 80))),
    TUT_DOOR_FATIGUE_EXPERT(door(213, 226, 760, 1, cacheAtLeast("tutorial", 90))),
    TUT_DOOR_COMMUNITY_INSTRUCT(door(142, 230, 759, 0, cacheAtLeast("tutorial", 100))),

    GATE_MEMBERS_RED_DRAGONS(everySceneryDoor(93, membersWorld())),
    GATE_MEMBERS_TAVERLEY(everySceneryDoor(137, membersWorld())),
    GATE_MEMBERS_CRAFT_VAULT(everySceneryDoor(138, membersWorld())),
    GATE_MEMBERS_KARAMJA(everySceneryDoor(254, membersWorld())),
    GATE_MEMBERS_EDGE_DUNG(everySceneryDoor(305, membersWorld())),
    GATE_MEMBERS_WILD_BLACK_DRG(everySceneryDoor(319, membersWorld())),
    GATE_MEMBERS_WILD_ICE_GIANT(everySceneryDoor(346, membersWorld())),
    GATE_MEMBERS_NE_WILDERNESS(everySceneryDoor(347, membersWorld())),
    GATE_MEMBERS_WILD_KBD(everySceneryDoor(508, membersWorld())),
    GATE_MEMBERS_DIGSITE(everySceneryDoor(1089, membersWorld())),

    /**
     * From y &lt;= 472 it opens where the woodcutting guild is on; from the other side the guild's
     * own gate handler takes over.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1227">DoorAction.handleGates</a>
     */
    GATE_MCGRUBORS_WOOD(onlyIn(
            feature(WorldProfile.Feature.WOODCUTTING_GUILD),
            sceneryDoor(356, 560, 472, 2, Prereq.ALWAYS, Prereq.NEVER))),

    GATE_COMBAT_CAMP(everySceneryDoor(563, questDone(Quest.BIOHAZARD))),
    GATE_KARAMJA_GLIDER(sceneryDoor(660, 387, 760, 2, Prereq.ALWAYS)),

    GATE_GNOME_BALL(everySceneryDoorBySide(
            702, (p, from) -> from.y() > 456, Prereq.ALWAYS, notCarrying(ItemId.GNOME_BALL.id()))),

    /**
     * Worn leather or ice gloves open it; with them on, worn metal armour does not shock.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/WitchesHouse.java#L267">WitchesHouse.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/WitchesHouse.java#L490">WitchesHouse.wearingMetalArmour</a>
     */
    GATE_WITCHS_ELECTRIC(
            sceneryDoor(256, 363, 3325, 2, any(wearing(ItemId.LEATHER_GLOVES.id()), wearing(ItemId.ICE_GLOVES.id())))),

    GATE_MOURNERS_HOUSE(everySceneryDoorBySide(
            504,
            (p, from) -> from.x() <= 630,
            Prereq.ALWAYS,
            carrying(ItemId.BIOHAZARD_BRONZE_KEY.id()),
            openedWith(ItemId.BIOHAZARD_BRONZE_KEY.id()))),

    /**
     * Below stage 11 the gate still lets the player out from y &gt;= 3448, to (637,3447).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/PlagueCity.java#L947">PlagueCity.onOpLoc</a>
     */
    GATE_NEAR_ELENA(sceneryDoor(457, 637, 3447, 2, questStage(Quest.PLAGUE_CITY, 11), Prereq.ALWAYS)),
    GATE_NEAR_ELENA_LITTLE_KEY(sceneryDoor(
            457, 637, 3447, 2, carrying(ItemId.LITTLE_KEY.id()), Prereq.NEVER, openedWith(ItemId.LITTLE_KEY.id()))),

    GATE_NEAR_GOLRIE(everySceneryDoorBySide(
            480,
            (p, from) -> from.y() <= 3529,
            questStage(Quest.WATERFALL_QUEST, 1),
            carrying(ItemId.LARGE_KEY.id()),
            openedWith(ItemId.LARGE_KEY.id()))),
    /**
     * Without the amulet the corridor floods and washes the player out to (654,485).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L456">Waterfall_Quest.onOpLoc</a>
     */
    DOORS_WATERFALL_CLOSED(everySceneryDoor(471, wearing(ItemId.GLARIALS_AMULET.id()))),
    ;

    /**
     * The player-cache key naming the Shield of Arrav gang a player joined: {@link #ARRAV_BLACK_ARM}
     * or {@link #ARRAV_PHOENIX}. Absent until the quest passes stage 3, never removed.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ShieldOfArrav.java#L30-L33">ShieldOfArrav.BLACK_ARM</a>
     */
    public static final String ARRAV_GANG = "arrav_gang";

    public static final int ARRAV_BLACK_ARM = 0;

    public static final int ARRAV_PHOENIX = 1;

    /** The Shield of Arrav stage of a player who completed it in the Phoenix Gang. */
    public static final int ARRAV_DONE_PHOENIX = -1;

    /**
     * The Shield of Arrav stage of a player who completed it in the Black Arm Gang.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/varrock/King.java#L41-L43">King.onTalkNpc</a>
     */
    public static final int ARRAV_DONE_BLACK_ARM = -2;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    SpecialDoor(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    /**
     * The arm from the flank with the smaller coordinate to the one with the larger, at every
     * placement of this door: toward the higher coordinate, which is into a room at one placement and
     * out of it at another.
     */
    public TransportRef lowToHigh() {
        return new TransportRef.Arm(armKey(name(), true));
    }

    /**
     * The opposite arm to {@link #lowToHigh()}.
     */
    public TransportRef highToLow() {
        return new TransportRef.Arm(armKey(name(), false));
    }

    /**
     * The key one arm of door {@code id} is addressed by: {@code id#lowToHigh} or {@code id#highToLow}.
     * Every placement of the door shares the key of each direction.
     */
    public static String armKey(String id, boolean lowToHigh) {
        return id + (lowToHigh ? "#lowToHigh" : "#highToLow");
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (SpecialDoor d : values()) {
            out.addAll(d.expand(env));
        }
        return out;
    }

    private static List<Door> picklock94(EveryPlacement.Placed p) {
        boolean listed = p.at().equals(Point.of(586, 581))
                || p.at().equals(Point.of(539, 599))
                || p.at().equals(Point.of(581, 580))
                || p.at().equals(Point.of(581, 761));
        if (!listed) {
            return List.of(door(
                    94,
                    p.x(),
                    p.z(),
                    p.dir(),
                    skill(Skill.THIEVING, 1),
                    note("a roll at requirement 1; retried only where batch_progression is on")));
        }
        return Door.bySide(
                true,
                p,
                (q, from) -> (from.x() == 539 && from.y() >= 599)
                        || (from.x() <= 585 && from.y() == 581)
                        || (from.x() >= 581 && from.y() == 580)
                        || (from.x() == 582 && from.y() >= 761)
                        || (from.x() == 581 && from.y() >= 762),
                Prereq.ALWAYS,
                skill(Skill.THIEVING, 16));
    }

    private static List<Door> landingBySide(
            EveryPlacement.Placed p, Prereq gate, UnaryOperator<Point> landing, Option... options) {
        return landingBySide(p, (q, from) -> true, gate, gate, landing, options);
    }

    private static List<Door> landingBySide(
            EveryPlacement.Placed p,
            Door.Side side,
            Prereq ifSide,
            Prereq otherwise,
            UnaryOperator<Point> landing,
            Option... options) {
        Point low = Door.lowFlank(true, p.x(), p.z(), p.dir());
        Point high = Door.highFlank(true, p.x(), p.z(), p.dir());
        if (low == null || high == null) {
            return List.of();
        }
        Point up = landing.apply(low);
        Point down = landing.apply(high);
        Option[] all = Arrays.copyOf(options, options.length + 2);
        all[options.length] = lowToHighLandsAt(up.x(), up.y());
        all[options.length + 1] = highToLowLandsAt(down.x(), down.y());
        return List.of(door(
                p.objectId(),
                p.x(),
                p.z(),
                p.dir(),
                side.test(p, low) ? ifSide : otherwise,
                side.test(p, high) ? ifSide : otherwise,
                all));
    }

    /**
     * The quests a door's gate names, by the server's quest id; -1 for a quest the server has no id
     * for, whose gates never pass.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Quests.java">Quests</a>
     */
    public enum Quest {
        SHIELD_OF_ARRAV(QuestId.SHIELD_OF_ARRAV.id()),
        ERNEST_THE_CHICKEN(QuestId.ERNEST_THE_CHICKEN.id()),
        DRAGON_SLAYER(QuestId.DRAGON_SLAYER.id()),
        DRUIDIC_RITUAL(QuestId.DRUIDIC_RITUAL.id()),
        WITCHES_HOUSE(QuestId.WITCHS_HOUSE.id()),
        FAMILY_CREST(QuestId.FAMILY_CREST.id()),
        LOST_CITY(QuestId.LOST_CITY.id()),
        HEROES_QUEST(QuestId.HEROS_QUEST.id()),
        MERLINS_CRYSTAL(QuestId.MERLINS_CRYSTAL.id()),
        SCORPION_CATCHER(QuestId.SCORPION_CATCHER.id()),
        TRIBAL_TOTEM(QuestId.TRIBAL_TOTEM.id()),
        TEMPLE_OF_IKOV(QuestId.TEMPLE_OF_IKOV.id()),
        PLAGUE_CITY(QuestId.PLAGUE_CITY.id()),
        BIOHAZARD(QuestId.BIOHAZARD.id()),
        GRAND_TREE(QuestId.GRAND_TREE.id()),
        GERTRUDES_CAT(QuestId.GERTRUDES_CAT.id()),
        SEA_SLUG(QuestId.SEA_SLUG.id()),
        MURDER_MYSTERY(QuestId.MURDER_MYSTERY.id()),
        TOURIST_TRAP(QuestId.TOURIST_TRAP.id()),
        UNDERGROUND_PASS(QuestId.UNDERGROUND_PASS.id()),
        LEGENDS_QUEST(QuestId.LEGENDS_QUEST.id()),
        WATCHTOWER(QuestId.WATCHTOWER.id()),
        DWARF_CANNON(QuestId.DWARF_CANNON.id()),
        BLACK_KNIGHTS_FORTRESS(QuestId.BLACK_KNIGHTS_FORTRESS.id()),
        MELZARS_MAZE(-1),
        CLOCK_TOWER(QuestId.CLOCK_TOWER.id()),
        WATERFALL_QUEST(QuestId.WATERFALL_QUEST.id()),
        FIGHT_ARENA(QuestId.FIGHT_ARENA.id()),
        FISHING_CONTEST(QuestId.FISHING_CONTEST.id()),
        JUNGLE_POTION(QuestId.JUNGLE_POTION.id()),
        COOKS_ASSISTANT(QuestId.COOKS_ASSISTANT.id()),
        SHILO_VILLAGE(QuestId.SHILO_VILLAGE.id()),
        PRINCE_ALI_RESCUE(QuestId.PRINCE_ALI_RESCUE.id()),
        THE_HOLY_GRAIL(QuestId.THE_HOLY_GRAIL.id()),
        WITCHS_POTION(QuestId.WITCHS_POTION.id()),
        WANTED(-1),
        ROVING_ELVES(-1);

        public final int serverId;

        Quest(int serverId) {
            this.serverId = serverId;
        }
    }

    // A members' gate asks only for a members' world, which Uranium and Coleslaw both are:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1451
    private static Prereq membersWorld() {
        return Prereq.ALWAYS;
    }

    private static Prereq skill(Skill s, int level) {
        return Prereq.fact(Fact.skillAtLeast(s, level));
    }

    private static Prereq carrying(int itemId) {
        return Prereq.fact(Fact.carriedAtLeast(itemId, 1));
    }

    private static Prereq notCarrying(int itemId) {
        return Prereq.fact(Fact.notCarrying(itemId));
    }

    private static Prereq carryingCount(int itemId, int count) {
        return Prereq.fact(Fact.carriedAtLeast(itemId, count));
    }

    private static Prereq wearing(int itemId) {
        return Prereq.fact(Fact.wearing(itemId));
    }

    private static Prereq all(Prereq... ps) {
        return Prereq.all(ps);
    }

    private static Prereq any(Prereq... ps) {
        return Prereq.any(ps);
    }

    private static Prereq cacheOn(String key) {
        return Prereq.fact(Fact.cacheBool(key, true));
    }

    private static Prereq cacheOff(String key) {
        return Prereq.fact(Fact.cacheBool(key, false));
    }

    private static Prereq cacheAtLeast(String key, int min) {
        return Prereq.fact(Fact.cacheIntAtLeast(key, min));
    }

    private static Prereq cacheIs(String key, int value) {
        return Prereq.fact(Fact.cacheIntEquals(key, value));
    }

    private static Prereq questStage(Quest q, int minStage) {
        return q.serverId < 0 ? Prereq.NEVER : Prereq.fact(Fact.questAtLeast(q.serverId, minStage));
    }

    private static Prereq questAt(Quest q, int stage) {
        return q.serverId < 0 ? Prereq.NEVER : Prereq.fact(Fact.questEquals(q.serverId, stage));
    }

    private static Prereq questDone(Quest q) {
        return q.serverId < 0 ? Prereq.NEVER : Prereq.fact(Fact.questComplete(q.serverId));
    }

    private static Prereq questPts(int min) {
        return Prereq.fact(Fact.questPointsAtLeast(min));
    }
}
