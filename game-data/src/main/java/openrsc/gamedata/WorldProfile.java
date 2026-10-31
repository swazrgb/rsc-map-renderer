package openrsc.gamedata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.landscape.LandscapeSource;

/**
 * Which world to load from a server conf tree: the conf switches that choose its locs files, NPC
 * spawn edits and landscape, and the {@link ServerRules} its server plays by. Read from a world's
 * own {@code .conf} with {@link #fromConf}, or built with {@link #builder}. Immutable.
 *
 * <p>The locs file sets mirror the server's {@code WorldPopulator}, in its order: later files add
 * to earlier ones.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L45">WorldPopulator.populateWorld</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L192">WorldPopulator.loadCustomLocs</a>
 */
public final class WorldProfile {

    /**
     * A conf switch that changes which data loads. {@link #key()} is the conf key, read as the
     * server reads it (absent is off); {@code null} for a feature derived from other settings.
     */
    public enum Feature {
        CUSTOM_LANDSCAPE("custom_landscape"),
        CUSTOM_SPRITES("custom_sprites"),
        FIXED_BROKEN_MECHANICS("want_fixed_broken_mechanics"),
        DECORATED_MOD_ROOM("want_decorated_mod_room"),
        RUNECRAFT("want_runecraft"),
        HARVESTING("want_harvesting"),
        CUSTOM_QUESTS("want_custom_quests"),
        MISSING_GUILD_GREETINGS("want_missing_guild_greetings"),
        WOODCUTTING_GUILD("want_woodcutting_guild"),
        MICE_TO_MEET_YOU("mice_to_meet_you"),
        DEATH_ISLAND("death_island"),
        OPENPK_POINTS("want_openpk_points"),
        PK_BOTS("want_pk_bots"),
        AUCTION_NPCS("spawn_auction_npcs"),
        IRONMAN_NPCS("spawn_iron_man_npcs"),
        ESTERS_BUNNIES("esters_bunnies"),
        LUMBRIDGE_CAROL("a_lumbridge_carol"),
        ARMY_OF_OBSCURITY("army_of_obscurity"),
        A_BONE_TO_PICK("a_bone_to_pick"),
        COMBAT_ODYSSEY("want_combat_odyssey"),
        /**
         * The server loads its custom-quest NPC locs: {@code location_data} 2 and
         * {@code want_custom_quests}. Derived when a profile is built; enabling it by hand has no
         * effect.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L266">WorldPopulator.loadCustomLocs</a>
         */
        CUSTOM_QUESTS_LOCS(null);

        private final String key;

        Feature(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /**
     * Builds a profile for a world with no {@code .conf}. Unset values: map revision 64, locs set 1
     * (authentic), a members world, view distance 2, {@link ServerRules#serverDefaults()}, no features,
     * and the name as {@code dbName}.
     */
    public static final class Builder {

        private final String name;
        private String dbName;
        private int basedMapData = 64;
        private int locationData = 1;
        private boolean memberWorld = true;
        private int viewDistance = DEFAULT_VIEW_DISTANCE;
        private ServerRules rules = ServerRules.serverDefaults();
        private final Set<Feature> features = EnumSet.noneOf(Feature.class);

        private Builder(String name) {
            this.name = name;
        }

        public Builder dbName(String dbName) {
            this.dbName = dbName;
            return this;
        }

        public Builder basedMapData(int rev) {
            this.basedMapData = rev;
            return this;
        }

        public Builder locationData(int locationData) {
            this.locationData = locationData;
            return this;
        }

        public Builder memberWorld(boolean memberWorld) {
            this.memberWorld = memberWorld;
            return this;
        }

        /** In 8-tile chunks. */
        public Builder viewDistance(int gridRadius) {
            this.viewDistance = gridRadius;
            return this;
        }

        public Builder rules(ServerRules worldRules) {
            this.rules = Objects.requireNonNull(worldRules, "rules");
            return this;
        }

        public Builder enable(Feature... toEnable) {
            features.addAll(Arrays.asList(toEnable));
            return this;
        }

        public WorldProfile build() {
            return new WorldProfile(
                    name,
                    dbName != null ? dbName : name,
                    basedMapData,
                    locationData,
                    memberWorld,
                    viewDistance,
                    rules,
                    features);
        }
    }

    private static final int NPC_TRAMP = 28;

    private static final int NPC_DUKE_OF_LUMBRIDGE = 198;
    private static final int NPC_SHILOP = 715;
    private static final int NPC_MUM = 812;
    private static final int NPC_BUNNY = 814;
    private static final int NPC_DEATH = 817;
    private static final int NPC_PRAETERITUM = 830;
    private static final int NPC_PRAESENS = 831;
    private static final int NPC_FUTURUM = 832;
    private static final int NPC_ASH = 835;

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L506
    private static final int DEFAULT_VIEW_DISTANCE = 2;

    /** The system property {@link #resolve} reads the world's conf name from. */
    public static final String WORLD_PROPERTY = "openrsc.world";

    /** The environment variable {@link #resolve} falls back to when {@link #WORLD_PROPERTY} is unset. */
    public static final String WORLD_ENV = "OPENRSC_WORLD";

    private static final WorldProfile AUTHENTIC = builder("uranium")
            .locationData(1)
            .enable(Feature.FIXED_BROKEN_MECHANICS)
            .rules(ServerRules.uranium())
            .build();

    private final String name;
    private final String dbName;
    private final int basedMapData;
    private final int locationData;
    private final boolean memberWorld;
    private final int viewDistance;
    private final ServerRules rules;
    private final Set<Feature> features;

    private WorldProfile(
            String name,
            String dbName,
            int basedMapData,
            int locationData,
            boolean memberWorld,
            int viewDistance,
            ServerRules rules,
            Set<Feature> features) {
        this.name = name;
        this.dbName = dbName;
        this.basedMapData = basedMapData;
        this.locationData = locationData;
        this.memberWorld = memberWorld;
        this.viewDistance = viewDistance;
        this.rules = rules;
        this.features = Set.copyOf(withDerived(features, locationData));
    }

    private static Set<Feature> withDerived(Set<Feature> features, int locationData) {
        Set<Feature> out = features.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(features);
        if (locationData == 2 && out.contains(Feature.CUSTOM_QUESTS)) {
            out.add(Feature.CUSTOM_QUESTS_LOCS);
        } else {
            out.remove(Feature.CUSTOM_QUESTS_LOCS);
        }
        return out;
    }

    /**
     * The authentic world as {@code uranium.conf} sets it up, without reading it: locs set 1 with the
     * discontinued locs, the JAG landscape, and {@link ServerRules#uranium()}. One shared instance.
     */
    public static WorldProfile authentic() {
        return AUTHENTIC;
    }

    /**
     * Reads {@code <world>.conf} from the server directory beside {@code conf}'s tree, such as
     * {@code uranium} or {@code rsccoleslaw}.
     *
     * @throws IOException when there is no such file or it cannot be read
     */
    public static WorldProfile fromConf(ServerConf conf, String world) throws IOException {
        Path file = conf.serverDir().resolve(world + ".conf");
        if (!Files.exists(file)) {
            throw new IOException("No server conf named '" + world + "' at " + file);
        }
        return fromConfFile(file);
    }

    /**
     * Reads a world's {@code .conf} at an explicit path; the world is named after the file. Each
     * absent key takes the server's default.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L376">ServerConfiguration.initConfig</a>
     */
    public static WorldProfile fromConfFile(Path confFile) throws IOException {
        return fromConfFile(confFile, Map.of());
    }

    /** As {@link #fromConfFile(Path)}, with {@code overrides} taking precedence over the file's values. */
    public static WorldProfile fromConfFile(Path confFile, Map<String, String> overrides) throws IOException {
        // The first occurrence of a key wins, so the overrides go first.
        List<String> lines = new ArrayList<>();
        overrides.forEach((key, value) -> lines.add(key + ": " + value));
        lines.addAll(Files.readAllLines(confFile));
        ServerProperties props = ServerProperties.parse(lines);
        String fileName = confFile.getFileName().toString();
        String world =
                fileName.endsWith(".conf") ? fileName.substring(0, fileName.length() - ".conf".length()) : fileName;

        Set<Feature> features = EnumSet.noneOf(Feature.class);
        for (Feature f : Feature.values()) {
            if (f.key() != null && props.bool(f.key(), false)) {
                features.add(f);
            }
        }
        return new WorldProfile(
                world,
                props.string("db_name").orElse("preservation"),
                props.integer("based_map_data", 100),
                props.integer("location_data", 0),
                props.bool("member_world", true),
                props.integer("view_distance", DEFAULT_VIEW_DISTANCE),
                ServerRules.from(props),
                features);
    }

    /**
     * The world named by {@link #WORLD_PROPERTY}, else {@link #WORLD_ENV}, read with
     * {@link #fromConf}; {@link #authentic()} when neither is set.
     */
    public static WorldProfile resolve(ServerConf conf) throws IOException {
        String world = System.getProperty(WORLD_PROPERTY);
        if (world == null || world.isBlank()) {
            world = System.getenv(WORLD_ENV);
        }
        return world == null || world.isBlank() ? authentic() : fromConf(conf, world.trim());
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** The conf file's name without {@code .conf} ({@code rsccoleslaw}), or the builder's name. */
    public String name() {
        return name;
    }

    /** The conf's {@code db_name}, the name of the world's database ({@code coleslaw}). */
    public String dbName() {
        return dbName;
    }

    /** The conf's {@code based_map_data}: the JAG map revision and the authentic locs files it selects. */
    public int basedMapData() {
        return basedMapData;
    }

    /** The conf's {@code location_data}, which selects the locs file set: 1 authentic, 2 custom, 4 OpenPK. */
    public int locationData() {
        return locationData;
    }

    public boolean memberWorld() {
        return memberWorld;
    }

    public ServerRules rules() {
        return rules;
    }

    public boolean has(Feature feature) {
        return features.contains(feature);
    }

    public Set<Feature> features() {
        return features;
    }

    public boolean customSprites() {
        return has(Feature.CUSTOM_SPRITES);
    }

    /**
     * The conf's {@code view_distance}, in 8-tile chunks. The server sends scenery, boundaries and
     * ground items within a square of this many chunks around the player, in chunk coordinates.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1065">GameStateUpdater.updateGameObjects</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L219">Point.withinGridRange</a>
     */
    public int viewDistance() {
        return viewDistance;
    }

    /**
     * The radius in tiles within which the server sends NPCs and players: {@code view_distance * 8 - 1},
     * 15 by default, measured as the truncated Pythagorean distance.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L182">GameStateUpdater.updateNpcs</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369">Mob.withinRange</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L409">Point.getDistancePythagoras</a>
     */
    public int mobRange() {
        return viewDistance * 8 - 1;
    }

    /**
     * The radius in 8-tile chunks within which an object the server did not send is taken as absent:
     * {@link #viewDistance()}, capped at 4.
     */
    public int liveViewGridRadius() {
        return Math.min(viewDistance, 4);
    }

    /**
     * Whether tile {@code (tileX, tileZ)} is within {@code gridRadius} chunks of the observer on both
     * axes, in chunk coordinates: the square {@link #viewDistance()} describes, so the server would
     * have sent an object there. Pass {@link #liveViewGridRadius()} for the world in play.
     */
    public static boolean absenceIsConclusive(int tileX, int tileZ, int obsX, int obsZ, int gridRadius) {
        return Math.abs((tileX >> 3) - (obsX >> 3)) <= gridRadius && Math.abs((tileZ >> 3) - (obsZ >> 3)) <= gridRadius;
    }

    /**
     * Opens the landscape the server loads: the JAG archives of {@code based_map_data}, or with
     * {@code custom_landscape} on, {@code Custom_Landscape.orsc} on a members world and
     * {@code F2PLandscape.orsc} otherwise. The caller closes it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L456">WorldLoader.loadWorld</a>
     */
    public LandscapeSource openLandscape(ServerConf conf) throws IOException {
        if (!has(Feature.CUSTOM_LANDSCAPE)) {
            return LandscapeSource.fromJag(conf.data().resolve("maps"), basedMapData, memberWorld);
        }
        return LandscapeSource.fromOrsc(
                conf.data().resolve(memberWorld ? "Custom_Landscape.orsc" : "F2PLandscape.orsc"));
    }

    /**
     * The boundary locs files the server loads for this world, in its order. Here and in the other
     * locs lists a file the tree does not ship is left out; the server logs it and goes on.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L415">WorldPopulator.loadGameObjLocs</a>
     */
    public List<Path> boundaryLocs(ServerConf conf) {
        List<Path> files = new ArrayList<>();
        files.add(conf.locs().resolve("BoundaryLocs" + authenticSuffix() + ".json"));
        if (locationData == 2 && (has(Feature.CUSTOM_QUESTS) || has(Feature.DEATH_ISLAND))) {
            files.add(conf.locs().resolve("BoundaryLocsCustomQuest.json"));
        }
        return existing(files);
    }

    /** The scenery locs files the server loads for this world, in its order. */
    public List<Path> sceneryLocs(ServerConf conf) {
        List<Path> files = new ArrayList<>();
        files.add(conf.locs().resolve("SceneryLocs" + authenticSuffix() + ".json"));
        if (locationData == 4 && has(Feature.OPENPK_POINTS)) {
            files.add(conf.locs().resolve("SceneryLocsOpenPk.json"));
        }
        if ((locationData == 1 || locationData == 2) && has(Feature.FIXED_BROKEN_MECHANICS)) {
            files.add(conf.locs().resolve("SceneryLocsDiscontinued.json"));
        }
        if (locationData == 2) {
            if (has(Feature.DECORATED_MOD_ROOM)) {
                files.add(conf.locs().resolve("SceneryLocsModRoom.json"));
            }
            if (has(Feature.RUNECRAFT)) {
                files.add(conf.locs().resolve("SceneryLocsRunecraft.json"));
            }
            if (has(Feature.HARVESTING)) {
                files.add(conf.locs().resolve("SceneryLocsHarvesting.json"));
            }
            if (has(Feature.CUSTOM_QUESTS)) {
                files.add(conf.locs().resolve("SceneryLocsCustomQuest.json"));
                files.add(conf.locs().resolve("SceneryLocsExpansion.json"));
            }
            if (has(Feature.MICE_TO_MEET_YOU)) {
                files.add(conf.locs().resolve("SceneryLocsMiceToMeetYou.json"));
            }
            if (has(Feature.WOODCUTTING_GUILD)) {
                files.add(conf.locs().resolve("SceneryLocsWoodcuttingGuild.json"));
            }
            files.add(conf.locs().resolve("SceneryLocsOther.json"));
        }
        return existing(files);
    }

    /** The NPC locs files the server loads for this world, in its order; see {@link #applyNpcFixups}. */
    public List<Path> npcLocs(ServerConf conf) {
        List<Path> files = new ArrayList<>();
        files.add(conf.locs().resolve("NpcLocs" + authenticSuffix() + ".json"));
        if ((locationData == 1 || locationData == 2) && has(Feature.FIXED_BROKEN_MECHANICS)) {
            files.add(conf.locs().resolve("NpcLocsDiscontinued.json"));
        }
        if (locationData == 4) {
            if (has(Feature.PK_BOTS)) {
                files.add(conf.locs().resolve("NpcLocsPkBots.json"));
            }
            if (has(Feature.OPENPK_POINTS)) {
                files.add(conf.locs().resolve("NpcLocsOpenPk.json"));
            }
        }
        if (locationData == 2) {
            if (has(Feature.DECORATED_MOD_ROOM)) {
                files.add(conf.locs().resolve("NpcLocsModRoom.json"));
            }
            if (has(Feature.AUCTION_NPCS)) {
                files.add(conf.locs().resolve("NpcLocsAuction.json"));
            }
            if (has(Feature.IRONMAN_NPCS)) {
                files.add(conf.locs().resolve("NpcLocsIronman.json"));
            }
            if (has(Feature.RUNECRAFT)) {
                files.add(conf.locs().resolve("NpcLocsRunecraft.json"));
            }
            if (has(Feature.HARVESTING)) {
                files.add(conf.locs().resolve("NpcLocsHarvesting.json"));
            }
            if (has(Feature.CUSTOM_QUESTS)) {
                files.add(conf.locs().resolve("NpcLocsCustomQuest.json"));
            }
            files.add(conf.locs().resolve("NpcLocsOther.json"));
        }
        return existing(files);
    }

    /**
     * The ground item locs files the server loads for this world, in its order. A spawn replaces any
     * earlier one on its tile, in the same file or an earlier one.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L309">WorldPopulator.loadCustomLocs</a>
     */
    public List<Path> groundItemLocs(ServerConf conf) {
        List<Path> files = new ArrayList<>();
        files.add(conf.locs().resolve("GroundItems" + authenticSuffix() + ".json"));
        if (locationData == 2) {
            if (has(Feature.HARVESTING)) {
                files.add(conf.locs().resolve("GroundItemsHarvesting.json"));
            }
            if (has(Feature.CUSTOM_QUESTS)) {
                files.add(conf.locs().resolve("GroundItemsCustomQuest.json"));
            }
        }
        if (has(Feature.MICE_TO_MEET_YOU)) {
            files.add(conf.locs().resolve("GroundItemsMiceToMeetYou.json"));
        }
        return existing(files);
    }

    /**
     * The spawn edits the server makes on a custom-quest world for each event that is off: the bunnies
     * move to the top floor of Ester's house, and Death in Varrock, the Rising Sun party guests and
     * Ash are removed. Returns {@code spawns} itself on any other world.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L270">WorldPopulator.loadCustomLocs</a>
     */
    public List<NpcLocs.Spawn> applyNpcFixups(List<NpcLocs.Spawn> spawns) {
        if (locationData != 2 || !has(Feature.CUSTOM_QUESTS)) {
            return spawns;
        }
        List<NpcLocs.Spawn> out = new ArrayList<>(spawns.size());
        for (NpcLocs.Spawn s : spawns) {
            int id = s.id();
            if (!has(Feature.MICE_TO_MEET_YOU) && id == NPC_DEATH && s.start().x() < 600) {
                continue;
            }
            if (!has(Feature.LUMBRIDGE_CAROL)) {
                if (s.start().x() == 320
                        && (id == NPC_DUKE_OF_LUMBRIDGE || id == NPC_MUM || id == NPC_TRAMP || id == NPC_SHILOP)) {
                    continue;
                }
                if (id == NPC_PRAETERITUM || id == NPC_PRAESENS || id == NPC_FUTURUM) {
                    continue;
                }
            }
            if (!has(Feature.ARMY_OF_OBSCURITY) && id == NPC_ASH) {
                continue;
            }
            if (!has(Feature.ESTERS_BUNNIES) && id == NPC_BUNNY) {
                out.add(new NpcLocs.Spawn(
                        id, new NpcLocs.Pos(317, 1607), new NpcLocs.Pos(314, 1603), new NpcLocs.Pos(319, 1608)));
                continue;
            }
            out.add(s);
        }
        return out;
    }

    @Override
    public String toString() {
        return "WorldProfile[" + name + " map=" + basedMapData + " locs=" + locationData
                + (memberWorld ? " members" : " f2p") + " " + features + "]";
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L53
    private String authenticSuffix() {
        if (basedMapData == 14) {
            return "14";
        }
        return basedMapData == 27 ? "27" : "";
    }

    private static List<Path> existing(List<Path> files) {
        List<Path> out = new ArrayList<>(files.size());
        for (Path p : files) {
            if (Files.exists(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
