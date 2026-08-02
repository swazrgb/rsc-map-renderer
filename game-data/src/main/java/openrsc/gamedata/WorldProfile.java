package openrsc.gamedata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import openrsc.gamedata.landscape.LandscapeSource;

/**
 * Which OpenRSC <em>world</em> to load from a server conf tree — Uranium (authentic), Cabbage /
 * Coleslaw (custom), OpenPK, or one you define yourself.
 *
 * <p>A single {@link ServerConf} tree ships the data for every world; what differs is a handful of
 * switches in the server's own {@code .conf} file, and those switches select real, different data:
 *
 * <table border="1">
 *   <caption>The worlds that ship with the server</caption>
 *   <tr><th></th><th>Uranium</th><th>Cabbage / Coleslaw</th></tr>
 *   <tr><td>{@code location_data}</td><td>1</td><td>2</td></tr>
 *   <tr><td>landscape</td><td>JAG {@code maps64}</td><td>{@code Custom_Landscape.orsc}</td></tr>
 *   <tr><td>locs</td><td>base + Discontinued</td>
 *       <td>+ Runecraft, Harvesting, CustomQuest, Expansion, ModRoom, Auction, Ironman, …</td></tr>
 *   <tr><td>sprites</td><td>{@code Authentic_Sprites.orsc}</td><td>{@code Custom_Sprites.osar}</td></tr>
 * </table>
 *
 * <p>Two ways in. {@link #fromConf(ServerConf, String)} reads the server's own file, so the server
 * stays ground truth and a flag flipped there needs no change here:
 *
 * <pre>{@code
 * WorldProfile cabbage = WorldProfile.fromConf(conf, "rsccabbage");
 * GameEnvironment env  = GameEnvironment.load(conf, cabbage, DoorOverrides.NONE);
 * }</pre>
 *
 * <p>Or build one directly, for a world with no {@code .conf} of its own:
 *
 * <pre>{@code
 * WorldProfile custom = WorldProfile.builder("my-world")
 *     .locationData(2)
 *     .enable(Feature.CUSTOM_LANDSCAPE, Feature.RUNECRAFT, Feature.HARVESTING)
 *     .build();
 * }</pre>
 *
 * <p>The locs tables below mirror {@code WorldPopulator.populateWorld} +
 * {@code WorldPopulator.loadCustomLocs} literally, in the server's own order (order matters: locs
 * are appended, and later files add to — never replace — earlier ones).
 */
public final class WorldProfile {

  /**
   * A server switch that changes which world data loads. Constants carry the {@code .conf} key the
   * server reads them from, so {@link #fromConf} needs no separate mapping table.
   */
  public enum Feature {
    /** {@code custom_landscape} — load {@code Custom_Landscape.orsc} instead of the JAG archives. */
    CUSTOM_LANDSCAPE("custom_landscape"),
    /** {@code custom_sprites} — client reads {@code Custom_Sprites.osar}. */
    CUSTOM_SPRITES("custom_sprites"),
    /** {@code want_fixed_broken_mechanics} — adds the Discontinued scenery/npc locs. */
    FIXED_BROKEN_MECHANICS("want_fixed_broken_mechanics"),
    /** {@code want_decorated_mod_room} */
    DECORATED_MOD_ROOM("want_decorated_mod_room"),
    /** {@code want_runecraft} */
    RUNECRAFT("want_runecraft"),
    /** {@code want_harvesting} */
    HARVESTING("want_harvesting"),
    /** {@code want_custom_quests} — the big one: CustomQuest + Expansion locs. */
    CUSTOM_QUESTS("want_custom_quests"),
    /** {@code want_woodcutting_guild} */
    WOODCUTTING_GUILD("want_woodcutting_guild"),
    /** {@code mice_to_meet_you} */
    MICE_TO_MEET_YOU("mice_to_meet_you"),
    /** {@code death_island} */
    DEATH_ISLAND("death_island"),
    /** {@code want_openpk_points} */
    OPENPK_POINTS("want_openpk_points"),
    /** {@code want_pk_bots} */
    PK_BOTS("want_pk_bots"),
    /** {@code spawn_auction_npcs} */
    AUCTION_NPCS("spawn_auction_npcs"),
    /** {@code spawn_iron_man_npcs} */
    IRONMAN_NPCS("spawn_iron_man_npcs"),
    /** {@code esters_bunnies} — when OFF, the bunnies are herded into Ester's upper floor. */
    ESTERS_BUNNIES("esters_bunnies"),
    /** {@code a_lumbridge_carol} — when OFF, the Rising Sun christmas spawns are dropped. */
    LUMBRIDGE_CAROL("a_lumbridge_carol"),
    /** {@code army_of_obscurity} — when OFF, Ash is dropped. */
    ARMY_OF_OBSCURITY("army_of_obscurity");

    private final String key;

    Feature(String key) {
      this.key = key;
    }

    /** The {@code .conf} key the server reads this switch from. */
    public String key() {
      return key;
    }
  }

  /** NPC ids the {@code want_custom_quests} post-load fixups key on (server {@code NpcId}). */
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

  private final String name;
  private final int basedMapData;
  private final int locationData;
  private final boolean memberWorld;
  private final Set<Feature> features;

  private WorldProfile(String name, int basedMapData, int locationData, boolean memberWorld,
      Set<Feature> features) {
    this.name = name;
    this.basedMapData = basedMapData;
    this.locationData = locationData;
    this.memberWorld = memberWorld;
    this.features = features;
  }

  /** Identifier for logs and bake output paths (the conf name, when read from one). */
  public String name() {
    return name;
  }

  /** {@code based_map_data} — selects the JAG revision and the authentic locs file set. */
  public int basedMapData() {
    return basedMapData;
  }

  /** {@code location_data} — 1 authentic, 2 custom (Cabbage/Coleslaw), 4 OpenPK. */
  public int locationData() {
    return locationData;
  }

  /** {@code member_world} — F2P worlds load {@code F2PLandscape.orsc} and skip .mem sectors. */
  public boolean memberWorld() {
    return memberWorld;
  }

  public boolean has(Feature feature) {
    return features.contains(feature);
  }

  public Set<Feature> features() {
    return Set.copyOf(features);
  }

  /** The authentic world as Uranium runs it: JAG {@code maps64}, base locs + Discontinued. */
  public static WorldProfile authentic() {
    return builder("uranium").locationData(1).enable(Feature.FIXED_BROKEN_MECHANICS).build();
  }

  /**
   * Read a world from the server's own conf file — {@code <server>/<world>.conf}, beside the
   * {@code conf/} tree (e.g. {@code uranium}, {@code rsccabbage}, {@code rsccoleslaw}).
   *
   * @throws IOException if no such conf file exists
   */
  public static WorldProfile fromConf(ServerConf conf, String world) throws IOException {
    Path file = conf.serverDir().resolve(world + ".conf");
    if (!Files.exists(file)) {
      throw new IOException("No server conf named '" + world + "' at " + file);
    }
    return fromConfFile(file);
  }

  /** As {@link #fromConf} but from an explicit path; the world name is the filename stem. */
  public static WorldProfile fromConfFile(Path confFile) throws IOException {
    ServerProperties props = ServerProperties.load(confFile);
    String fileName = confFile.getFileName().toString();
    String world = fileName.endsWith(".conf")
        ? fileName.substring(0, fileName.length() - ".conf".length())
        : fileName;

    Set<Feature> features = EnumSet.noneOf(Feature.class);
    for (Feature f : Feature.values()) {
      // Same defaults the server applies for an absent key (all of these default false).
      if (props.bool(f.key(), false)) {
        features.add(f);
      }
    }
    return new WorldProfile(world,
        props.integer("based_map_data", 100),   // server default; uranium/cabbage both say 64
        props.integer("location_data", 0),
        props.bool("member_world", true),
        features);
  }

  /** System property naming the world to load; see {@link #resolve(ServerConf)}. */
  public static final String WORLD_PROPERTY = "openrsc.world";

  /** Environment fallback for {@link #WORLD_PROPERTY}. */
  public static final String WORLD_ENV = "OPENRSC_WORLD";

  /**
   * The world this JVM was asked for — {@code -Dopenrsc.world=rsccabbage} (or
   * {@code $OPENRSC_WORLD}), naming a conf file beside the server tree; {@link #authentic()} when
   * unset.
   *
   * <p>For entrypoints meant to be repointed (the map/asset bakes). Code that must agree with a
   * specific live server names its world directly instead.
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

  /** Hand-rolls a profile for a world that has no {@code .conf} of its own. */
  public static final class Builder {

    private final String name;
    private int basedMapData = 64;
    private int locationData = 1;
    private boolean memberWorld = true;
    private final Set<Feature> features = EnumSet.noneOf(Feature.class);

    private Builder(String name) {
      this.name = name;
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

    public Builder enable(Feature... toEnable) {
      features.addAll(Arrays.asList(toEnable));
      return this;
    }

    public Builder set(Feature feature, boolean on) {
      if (on) {
        features.add(feature);
      } else {
        features.remove(feature);
      }
      return this;
    }

    public WorldProfile build() {
      return new WorldProfile(name, basedMapData, locationData, memberWorld, features);
    }
  }

  /**
   * Open this world's landscape, mirroring {@code WorldLoader.loadWorld}: the JAG archives at
   * {@code based_map_data} unless {@code custom_landscape} is set, in which case the {@code .orsc}
   * repack — {@code Custom_Landscape.orsc} on a members world, {@code F2PLandscape.orsc} otherwise.
   *
   * <p>The caller owns the returned source and must close it.
   */
  public LandscapeSource openLandscape(ServerConf conf) throws IOException {
    if (!has(Feature.CUSTOM_LANDSCAPE)) {
      return LandscapeSource.fromJag(conf.data().resolve("maps"), basedMapData, memberWorld);
    }
    return LandscapeSource.fromOrsc(conf.data().resolve(
        memberWorld ? "Custom_Landscape.orsc" : "F2PLandscape.orsc"));
  }

  /**
   * The world's {@code custom_sprites} setting — whether a client connecting to it would read
   * {@code Custom_Sprites.osar} instead of {@code Authentic_Sprites.orsc}.
   *
   * <p><b>The bakes deliberately ignore this for art selection</b>: every world, authentic included,
   * is baked with the HD custom pack. Reported here because it faithfully describes the server, not
   * because it steers rendering — do not wire it into {@code Config.S_WANT_CUSTOM_SPRITES}, which
   * also gates the animation-definition table that {@code NpcDefsCustom.json} (loaded on every
   * world) depends on. See {@code WorldRenderer.configureCache} for the full trap.
   */
  public boolean customSprites() {
    return has(Feature.CUSTOM_SPRITES);
  }

  /** Suffix on the authentic locs filenames for this map revision ({@code ""}, {@code "14"}, {@code "27"}). */
  private String authenticSuffix() {
    if (basedMapData == 14) {
      return "14";
    }
    return basedMapData == 27 ? "27" : "";
  }

  /**
   * Boundary locs files, in load order. Mirrors {@code WorldPopulator} case {@code Boundary}.
   */
  public List<Path> boundaryLocs(ServerConf conf) {
    List<Path> files = new ArrayList<>();
    files.add(conf.locs().resolve("BoundaryLocs" + authenticSuffix() + ".json"));
    if (locationData == 2 && (has(Feature.CUSTOM_QUESTS) || has(Feature.DEATH_ISLAND))) {
      files.add(conf.locs().resolve("BoundaryLocsCustomQuest.json"));
    }
    return existing(files);
  }

  /**
   * Scenery locs files, in load order. Mirrors {@code WorldPopulator} case {@code Scenery}.
   */
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

  /**
   * NPC locs files, in load order. Mirrors {@code WorldPopulator} case {@code NPC}; the post-load
   * spawn fixups that case also applies live in {@link #applyNpcFixups}.
   */
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
   * The post-load NPC spawn edits {@code WorldPopulator} applies inside its
   * {@code WANT_CUSTOM_QUESTS} branch — relocating or removing spawns whose seasonal event is off.
   * Without these, a custom world bakes christmas/easter NPCs the running server would not spawn.
   *
   * @return the spawn list to use (a new list when anything changed)
   */
  public List<NpcLocs.Spawn> applyNpcFixups(List<NpcLocs.Spawn> spawns) {
    if (locationData != 2 || !has(Feature.CUSTOM_QUESTS)) {
      return spawns;
    }
    List<NpcLocs.Spawn> out = new ArrayList<>(spawns.size());
    for (NpcLocs.Spawn s : spawns) {
      int id = s.id();
      // Death in Varrock is removed in favour of the one on Death Island.
      if (!has(Feature.MICE_TO_MEET_YOU) && id == NPC_DEATH && s.start().x() < 600) {
        continue;
      }
      // Rising Sun Inn christmas party.
      if (!has(Feature.LUMBRIDGE_CAROL)) {
        if (s.start().x() == 320 && (id == NPC_DUKE_OF_LUMBRIDGE || id == NPC_MUM
            || id == NPC_TRAMP || id == NPC_SHILOP)) {
          continue;
        }
        if (id == NPC_PRAETERITUM || id == NPC_PRAESENS || id == NPC_FUTURUM) {
          continue;
        }
      }
      if (!has(Feature.ARMY_OF_OBSCURITY) && id == NPC_ASH) {
        continue;
      }
      // Bunnies are herded onto the top floor of Ester's house when the event is off.
      if (!has(Feature.ESTERS_BUNNIES) && id == NPC_BUNNY) {
        out.add(new NpcLocs.Spawn(id,
            new NpcLocs.Pos(317, 1607),
            new NpcLocs.Pos(314, 1603),
            new NpcLocs.Pos(319, 1608)));
        continue;
      }
      out.add(s);
    }
    return out;
  }

  /**
   * Drop files this conf tree does not ship. The server logs and continues on a missing locs file,
   * and several of the gated names ({@code SceneryLocsExpansion.json}) are absent from some trees.
   */
  private static List<Path> existing(List<Path> files) {
    List<Path> out = new ArrayList<>(files.size());
    for (Path p : files) {
      if (Files.exists(p)) {
        out.add(p);
      }
    }
    return out;
  }

  @Override
  public String toString() {
    return "WorldProfile[" + name + " map=" + basedMapData + " locs=" + locationData
        + (memberWorld ? " members" : " f2p") + " " + features + "]";
  }
}
