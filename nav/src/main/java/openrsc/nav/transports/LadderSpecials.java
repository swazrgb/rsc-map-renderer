package openrsc.nav.transports;

import static openrsc.bot.api.quest.QuestId.DWARF_CANNON;
import static openrsc.bot.api.quest.QuestId.FISHING_CONTEST;
import static openrsc.bot.api.quest.QuestId.SEA_SLUG;
import static openrsc.bot.api.quest.QuestId.WATCHTOWER;
import static openrsc.nav.transports.spec.Bespoke.bespoke;
import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.cacheAtLeast;
import static openrsc.nav.transports.spec.Rows.carrying;
import static openrsc.nav.transports.spec.Rows.level;
import static openrsc.nav.transports.spec.Rows.notQuestAtLeast;
import static openrsc.nav.transports.spec.Rows.notQuestComplete;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.questAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.when;
import static openrsc.nav.transports.spec.ScriptedClimb.anywhere;
import static openrsc.nav.transports.spec.ScriptedClimb.columnLadder;
import static openrsc.nav.transports.spec.ScriptedClimb.ladder;
import static openrsc.nav.transports.spec.WorldGate.feature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.OnlyIn;
import openrsc.nav.transports.spec.ScriptedClimb;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * The ladders, stairs, trapdoors and cave mouths whose server handler does something other than the
 * default climb: a branch of the default ladder handler, or a quest plugin that takes the object
 * over. A constant whose handler tests the object's tile is declared on that tile; one that matches
 * the id alone is every placement of it.
 *
 * <p>The default handler runs its first few branches (lair, 629, 621, lava forge, 41) before the
 * object's teleport-point override and the rest after it.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L111">Ladders.onObjectAction</a>
 */
public enum LadderSpecials implements TransportDef {
    /**
     * Down for anyone whose cache holds the miniquest's key at any value; it is set to 0 when the
     * miniquest starts.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L64">Ladders.onObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/minigames/DwarfRescue.java#L57">DwarfRescue.onTalkNpc</a>
     */
    DWARVEN_MINE_LAVA_FORGE(onlyIn(
            feature(WorldProfile.Feature.CUSTOM_QUESTS),
            ladder(223, 271, 3340, to(329, 3419), when(cacheAtLeast("miniquest_dwarf_youth_rescue", 0))))),
    LAVA_FORGE_DWARVEN_MINE(onlyIn(feature(WorldProfile.Feature.CUSTOM_QUESTS), ladder(5, 329, 3418, to(271, 3339)))),

    STAIRS_UP_629(anywhere(629, to(576, 3580))),
    STAIRS_UP_621(anywhere(621, to(606, 3556))),

    /**
     * A member of the order climbs at any Prayer level; anyone else joins on the spot with current
     * Prayer 31.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L178">Ladders.onObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L186">Ladders.onObjectAction</a>
     */
    PRAYER_GUILD_LADDER(ladder(
            198,
            251,
            468,
            to(251, 1411),
            when(Prereq.any(cache("prayer_guild"), level(Skill.PRAYER, 31))),
            answers("join"))),
    /**
     * Needs current Mining 60.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L210">Ladders.onObjectAction</a>
     */
    MINING_GUILD_LADDER(ladder(223, 274, 566, to(274, 3397), when(level(Skill.MINING, 60)))),

    /**
     * The server lands the player on a random tile of the 5 by 5 square around the declared
     * landing.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L228">Ladders.onObjectAction</a>
     */
    BLACK_HOLE_LADDER(anywhere(199, to(305, 3300), when(carrying(ItemId.DISK_OF_RETURNING.id(), 1)))),
    CHAMPIONS_GUILD_DOWNSTAIRS(ladder(6, 148, 1507, to(148, 563))),

    /**
     * Closed until the A Lumbridge Carol party, on worlds with that event switched on.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L91">Ladders.onObjectAction</a>
     */
    RISING_SUN_INN_UPSTAIRS(ladder(41, 316, 546, to(316, 1493), when(cache("carol.partyAccess")))),

    BRIMSTAIL_CAVE_STRONGHOLD(anywhere(667, to(730, 3334))),
    LAIR_LADDER_UP_98_2930(ladder(5, 98, 2930, to(243, 96))),
    LAIR_LADDER_UP_137_2932(ladder(5, 137, 2932, to(243, 96))),

    DRAYNOR_MANOR_TRAPDOOR_ERNEST(anywhere(130, to(223, 554))),

    /**
     * Every ship ladder lands at the Crandor dock when the player boarded there, else at Port Sarim.
     * This arm is ungated: an unknown cache value reads as passing, so a gate on not having boarded
     * at Crandor would close it to everyone.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L366">DragonSlayer.onOpLoc</a>
     */
    LADY_LUMBRIDGE_LEAVE_SHIP_LADDER(anywhere(227, to(259, 641))),
    /**
     * Not from the two lower decks: boarding at Crandor lands on the upper ones, and only boarding at
     * Port Sarim, which records Port Sarim, reaches the lower ones.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L374">DragonSlayer.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L347">DragonSlayer.onOpLoc</a>
     */
    LADY_LUMBRIDGE_LEAVE_SHIP_LADDER_CRANDOR(anywhere(
                    227,
                    to(409, 638),
                    when(cacheAtLeast("lumb_lady", 1)),
                    note("only if you boarded at the Crandor dock"))
            .except(Point.of(259, 3494), Point.of(281, 3494))),

    /**
     * "Go up" climbs only with the {@code trapy} key, which "Search for traps" at current Thieving 21
     * stores and each ascent removes; without it the player falls through a trap for 7 damage.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TribalTotem.java#L416">TribalTotem.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TribalTotem.java#L419">TribalTotem.onOpLoc</a>
     */
    ARDOUGNE_ATTIC_STAIRS_TRIBAL_TOTEM(ladder(
            331,
            563,
            587,
            to(563, 1534),
            when(Prereq.all(level(Skill.THIEVING, 21), cache("trapy"))),
            note("each ascent needs a fresh \"Search for traps\""))),

    /**
     * Every placement but the one at (426, 458), which leads to the eastern tunnel mouth instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/FishingContest.java#L712">FishingContest.onOpLoc</a>
     */
    WHITE_WOLF_MOUNTAIN_STAIRS_DOWN(
            anywhere(359, to(385, 3301), when(questComplete(FISHING_CONTEST))).except(Point.of(426, 458))),
    WHITE_WOLF_MOUNTAIN_STAIRS_DOWN_E(ladder(359, 426, 458, to(426, 3294), when(questComplete(FISHING_CONTEST)))),

    TEMPLE_OF_IKOV_STAIRS_UP(anywhere(369, to(536, 3338))),

    /**
     * A lit candle, black candle or torch lights the way down; without one the stairs stop in the
     * dark room.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TempleOfIkov.java#L477">TempleOfIkov.onOpLoc</a>
     */
    TEMPLE_OF_IKOV_STAIRS_DOWN(anywhere(370, to(537, 3372), when(Kit.LIT_LIGHT))),
    TEMPLE_OF_IKOV_STAIRS_DOWN_UNLIT(anywhere(
            370,
            to(537, 3394),
            when(Prereq.all(
                    Prereq.fact(Fact.notCarrying(ItemId.LIT_CANDLE.id())),
                    Prereq.fact(Fact.notCarrying(ItemId.LIT_BLACK_CANDLE.id())),
                    Prereq.fact(Fact.notCarrying(ItemId.LIT_TORCH.id())))))),

    /**
     * Free below stage 5 and once done; from stage 5 the fishermen throw back anyone without a lit
     * torch.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/SeaSlug.java#L644">SeaSlug.onOpLoc</a>
     */
    WITCHAVEN_PIER_LADDER_SEA_SLUG(anywhere(
            458,
            to(494, 1561),
            when(Prereq.any(
                    questComplete(SEA_SLUG), notQuestAtLeast(SEA_SLUG, 5), carrying(ItemId.LIT_TORCH.id(), 1))))),

    GRAND_TREE_UPPER_LADDER_UP(anywhere(585, to(417, 2994))),
    GRAND_TREE_UPPER_LADDER_DOWN(anywhere(586, to(415, 2051))),

    /**
     * Needs current Agility 25.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/grandtree/GrandTree.java#L1563">GrandTree.onOpLoc</a>
     */
    GRAND_TREE_WATCH_TOWER_UP(anywhere(635, to(710, 2364), when(level(Skill.AGILITY, 25)))),
    GRAND_TREE_WATCH_TOWER_DOWN(anywhere(646, to(712, 1420))),

    /**
     * The tower guard lets the player up from stage 1, and the climb then lands as a default ladder
     * does.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L84">WatchTowerObstacles.onOpLoc</a>
     */
    WATCH_TOWER_FIRST_FLOOR_LADDER_UP(everyScenery(
            659, p -> List.of(columnLadder(659, p.x(), p.z(), true, when(questAtLeast(WATCHTOWER, 1)))))),

    UNDERGROUND_PASS_DUNGEON_LADDER(anywhere(920, to(782, 3549))),

    OBSERVATORY_DUNGEON_LADDER(anywhere(928, to(712, 3512))),

    /**
     * The trapdoor stays shut at stage 0.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/DwarfCannon.java#L625">DwarfCannon.onOpLoc</a>
     */
    KHAZARD_TRAPDOOR_UP_DWARF_CANNON(anywhere(981, to(616, 1435), when(questAtLeast(DWARF_CANNON, 1)))),
    KHAZARD_TRAPDOOR_DOWN_DWARF_CANNON(anywhere(985, to(616, 493))),

    /**
     * The handler takes only a cave entrance whose y is 523.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/DwarfCannon.java#L617">DwarfCannon.blockOpLoc</a>
     */
    DWARF_CANNON_GOBLIN_CAVE_ENTER(everyScenery(
            982, p -> p.z() == 523 ? List.of(ladder(982, p.x(), p.z(), to(578, 3356))) : List.of())),
    DWARF_CANNON_GOBLIN_CAVE_EXIT_MUDPILE(anywhere(983, to(578, 521))),

    WATCH_TOWER_ISLAND_LADDER(anywhere(997, to(669, 826))),

    WATCH_TOWER_COMPLETED_QUEST_LADDER(anywhere(1017, to(636, 1684))),

    /**
     * Up to the tower top until the quest is done, then down to the Skavid caves instead.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L77">WatchTowerObstacles.onOpLoc</a>
     */
    WATCH_TOWER_SECOND_FLOOR_LADDER_UP(anywhere(1021, to(636, 2628), when(notQuestComplete(WATCHTOWER)))),
    WATCH_TOWER_SECOND_FLOOR_LADDER_COMPLETE(anywhere(1021, to(492, 3524), when(questComplete(WATCHTOWER)))),

    DIGSITE_SMALL_CAVE_ROPE(anywhere(1097, to(14, 506))),
    DIGSITE_BIG_CAVE_ROPE(anywhere(1098, to(25, 515))),

    /**
     * The ladder starts the nearby monk's conversation; accepting the risk climbs down one floor and
     * drops current Prayer to 3 or less.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/LostCity.java#L86">LostCity.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/LostCity.java#L338">LostCity.onTalkNpc</a>
     */
    ENTRANA_DUNGEON_LADDER(everyScenery(244, p -> List.of(columnLadder(244, p.x(), p.z(), false, answers("risk"))))),

    GNOME_AGILITY_COURSE_LANDING(anywhere(649, to(683, 506))),

    PARAMAYA_DORMITORY_LADDER_UP(bespoke(ParamayaDormitoryTransport::forWorld)),
    ;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    LadderSpecials(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    /**
     * The placements the constants here declare on their own tile, whatever the world; the default
     * climb leaves them alone.
     */
    public static Set<ScriptedClimb.Placement> scriptedPlacements() {
        return SCRIPTED;
    }

    private static final Set<ScriptedClimb.Placement> SCRIPTED = scripted();

    private static Set<ScriptedClimb.Placement> scripted() {
        Set<ScriptedClimb.Placement> out = new HashSet<>();
        for (LadderSpecials l : values()) {
            TransportSpec spec = l.spec;
            while (spec instanceof OnlyIn gated) {
                spec = gated.spec();
            }
            if (spec instanceof ScriptedClimb climb) {
                out.add(climb.placement());
            }
        }
        return Set.copyOf(out);
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (LadderSpecials l : values()) {
            out.addAll(l.expand(env));
        }
        return out;
    }
}
