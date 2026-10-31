package openrsc.nav.transports;

import static openrsc.bot.api.quest.QuestId.DRAGON_SLAYER;
import static openrsc.bot.api.quest.QuestId.LEGENDS_QUEST;
import static openrsc.bot.api.quest.QuestId.LOST_CITY;
import static openrsc.bot.api.quest.QuestId.WATCHTOWER;
import static openrsc.nav.transports.spec.AnyWallPlacement.anyWall;
import static openrsc.nav.transports.spec.EveryPlacement.everyBoundary;
import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.cacheAtLeast;
import static openrsc.nav.transports.spec.Rows.canKillAt;
import static openrsc.nav.transports.spec.Rows.from;
import static openrsc.nav.transports.spec.Rows.level;
import static openrsc.nav.transports.spec.Rows.questAt;
import static openrsc.nav.transports.spec.Rows.questAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.tiles;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.wearing;
import static openrsc.nav.transports.spec.Rows.when;
import static openrsc.nav.transports.spec.WallCrossing.wall;
import static openrsc.nav.transports.spec.WallCrossing.wallTeleport;
import static openrsc.nav.transports.spec.WallTeleport.railings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.Door;
import openrsc.nav.transports.spec.EveryPlacement;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;
import openrsc.nav.transports.spec.WallCrossing;

/**
 * Walls the server teleports the player through when operated: magic doors, portals, cave exits,
 * railings and wall jumps. A wall whose handler steps the player one tile through is a door instead.
 */
public enum WallCrossings implements TransportDef {
    // The shed door teleports to Zanaris only while the Dramen staff is worn, from stage 4, on a
    // members world; otherwise it steps into the shed.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/LostCity.java#L457
    ZANARIS_SHED_DOOR(anyWall(
            66, to(126, 3518), 80, when(Prereq.all(wearing(ItemId.DRAMEN_STAFF.id()), questAtLeast(LOST_CITY, 4))))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/LostCity.java#L453
    ENTRANA_DUNGEON_MAGIC_DOOR_EXIT(anyWall(65, to(109, 245), 80)),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L189
    GRAND_TREE_MAIN_DOOR_ENTER(anyWall(153, to(416, 165), 80)),
    GRAND_TREE_MAIN_DOOR_EXIT(anyWall(154, to(703, 455), 80)),

    // Steps through from a tile at x >= 414, or from anywhere at Dragon Slayer stage 3.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L458
    ELVARGS_CHAMBER_DOOR(everyBoundary(59, p -> {
                List<TransportSpec> out = new ArrayList<>();
                for (Point[] arm : stepThrough(p)) {
                    Prereq gate = arm[0].x() >= 414 ? Prereq.ALWAYS : questAt(DRAGON_SLAYER, 3);
                    out.add(wall(59, p.x(), p.z(), arm[0], arm[1], 20, when(gate)));
                }
                return out;
            })
            .facing(0, 1)),

    // Each portal id has one landing, wherever it stands.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/MagicGuildPortals.java#L23
    MAGIC_PORTAL_147(anyWall(147, to(212, 695), 80)),
    MAGIC_PORTAL_148(anyWall(148, to(511, 1452), 80)),
    MAGIC_PORTAL_149(anyWall(149, to(362, 1515), 80)),

    // No plugin handles these doors: the server moves the player by the telepoint at the door's tile.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectWallAction.java#L79
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_414_1107(wallTeleport(156, 414, 1107, 1, to(700, 1395))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_701_1395(wallTeleport(156, 701, 1395, 1, to(414, 1107))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_419_1107(wallTeleport(155, 419, 1107, 1, to(708, 1395))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_707_1395(wallTeleport(155, 707, 1395, 1, to(418, 1107))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_416_2050(wallTeleport(157, 416, 2050, 0, to(703, 2336))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_703_2337(wallTeleport(157, 703, 2337, 0, to(416, 2050))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_415_2051(wallTeleport(160, 415, 2051, 1, to(701, 2338))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_702_2338(wallTeleport(160, 702, 2338, 1, to(415, 2051))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_418_2051(wallTeleport(158, 418, 2051, 1, to(705, 2338))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_705_2338(wallTeleport(158, 705, 2338, 1, to(417, 2051))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_416_2053(wallTeleport(159, 416, 2053, 0, to(703, 2340))),
    TREE_GNOME_STRONGHOLD_MAGIC_DOOR_703_2340(wallTeleport(159, 703, 2340, 0, to(416, 2052))),

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L428
    WATCH_TOWER_CAVE_EXIT_NW(anyWall(188, to(648, 769), 80)),
    WATCH_TOWER_CAVE_EXIT_N(anyWall(189, to(638, 777), 80)),
    WATCH_TOWER_CAVE_EXIT_GU_TANOTH(anyWall(190, to(629, 777), 80)),
    WATCH_TOWER_CAVE_EXIT_E(anyWall(191, to(631, 789), 80)),
    WATCH_TOWER_CAVE_EXIT_SE(anyWall(187, to(624, 807), 80)),
    WATCH_TOWER_CAVE_EXIT_S(anyWall(192, to(645, 812), 80)),

    // Stated per tile: the handler sides the battlement by the fixed test x <= 664, which holds only
    // for these placements.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L451
    GU_TANOTH_SW_BATTLEMENT_811_W_E_FREE(wall(195, 665, 811, from(664, 811), to(665, 811), 80)),
    GU_TANOTH_SW_BATTLEMENT_812_W_E_FREE(wall(195, 665, 812, from(664, 812), to(665, 812), 80)),
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L454
    GU_TANOTH_SW_BATTLEMENT_811_E_W_GIFTED(wall(
            195,
            665,
            811,
            from(665, 811),
            to(664, 811),
            80,
            when(Prereq.any(cache("has_ogre_gift"), questComplete(WATCHTOWER))))),
    GU_TANOTH_SW_BATTLEMENT_812_E_W_GIFTED(wall(
            195,
            665,
            812,
            from(665, 812),
            to(664, 812),
            80,
            when(Prereq.any(cache("has_ogre_gift"), questComplete(WATCHTOWER))))),

    // Railing 168 is sealed shut and crosses nowhere.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L264
    UPASS_RAILING_167(railings(167, Prereq.ALWAYS)),
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L271
    UPASS_RAILING_169(railings(169, level(Skill.THIEVING, 50))),
    UPASS_RAILING_170(railings(170, Prereq.ALWAYS)),

    // The landing is picked by the jumper's y alone; a failed jump deals 6 damage and still lands.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L121
    LEGENDS_RUINED_WALL(everyBoundary(
                    211,
                    p -> Stream.of(p.plus(0, 1), p.plus(0, -1))
                            .map(e -> wall(
                                    211,
                                    p.x(),
                                    p.z(),
                                    e,
                                    e.y() >= 3729 ? to(457, 3727) : to(455, 3729),
                                    20,
                                    when(level(Skill.AGILITY, 50)),
                                    canKillAt(6)))
                            .toList())
            .facing(3)),

    // The handler sends the wall at (464,3721) into the pools cavern and every other one back to
    // (463,3720).
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L51
    LEGENDS_ANCIENT_WALL(wall(
            212,
            464,
            3721,
            tiles(from(464, 3720), from(464, 3722), from(463, 3721), from(465, 3721)),
            to(467, 3724),
            20,
            when(Prereq.any(cacheAtLeast("ancient_wall_runes", 5), questComplete(LEGENDS_QUEST))))),
    LEGENDS_ANCIENT_WALL_RETURN(anyWall(
            212,
            to(463, 3720),
            20,
            when(Prereq.any(cacheAtLeast("ancient_wall_runes", 5), questComplete(LEGENDS_QUEST))))),
    ;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    WallCrossings(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    /**
     * Whether a row here states boundary {@code boundaryId} at {@code (x, z)} by its tile, so an
     * any-placement row of the same id leaves that placement out.
     */
    public static boolean statesWall(int boundaryId, int x, int z) {
        return STATED.contains(new StatedWall(boundaryId, x, z));
    }

    private record StatedWall(int boundaryId, int x, int z) {}

    private static final Set<StatedWall> STATED = stated();

    private static Set<StatedWall> stated() {
        Set<StatedWall> out = new HashSet<>();
        for (WallCrossings w : values()) {
            if (w.spec instanceof WallCrossing wall) {
                out.add(new StatedWall(wall.boundaryId(), wall.x(), wall.z()));
            }
        }
        return Set.copyOf(out);
    }

    private static List<Point[]> stepThrough(EveryPlacement.Placed p) {
        Point low = Door.lowFlank(true, p.x(), p.z(), p.dir());
        Point high = Door.highFlank(true, p.x(), p.z(), p.dir());
        return List.of(new Point[] {high, low}, new Point[] {low, high});
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (WallCrossings w : values()) {
            out.addAll(w.expand(env));
        }
        return out;
    }
}
