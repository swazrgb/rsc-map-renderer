package openrsc.gamedata.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.BoundaryLocs;
import openrsc.gamedata.ItemDefs;
import openrsc.gamedata.NpcDefs;
import openrsc.gamedata.NpcLocs;
import openrsc.gamedata.SceneryLocs;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.api.ServerData;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.DoorOverrides;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.TileDefs;
import openrsc.gamedata.defs.extras.ExtraDefs;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bundles the world-derived data loaded from the OpenRSC server conf tree: defs, the collision map,
 * and the raw inputs (scenery/boundary locs, npc spawns, skill extras) that renderers and a
 * script-side path planner consume.
 *
 * <p>Loading is moderately expensive (the collision map alone is a few seconds cold). Cache the
 * result and reuse it across consumers.
 *
 * <p>A plain (non-record) class on purpose: its {@link ServerData} projection is memoised lazily
 * (below), which a record's final fields can't hold; and the path planner keys its danger-field
 * cache on {@code ServerData} <em>identity</em>, so a value-based {@code equals}/{@code hashCode}
 * (deep over the collision map + defs) would be both wrong and expensive here.
 */
public final class GameEnvironment {

  private static final Logger LOG = LoggerFactory.getLogger(GameEnvironment.class);

  private final ItemDefs itemDefs;
  private final DoorDefs doorDefs;
  private final TileDefs tileDefs;
  private final ObjectDefs objectDefs;
  private final NpcDefs npcDefs;
  private final CollisionMap collisionMap;
  private final List<SceneryLocs.Loc> sceneryLocs;
  /** Static boundary locs (walls, fences, doors). Retained so a GeoJSON exporter can render door
   *  points; collision has already consumed them into {@link #collisionMap}. */
  private final List<BoundaryLocs.Loc> boundaryLocs;
  private final Path ladderTelepointsXml;
  /** Skill-content tables from {@code defs/extras/*.xml} — surfaced via {@link #serverData()}. */
  private final ExtraDefs extraDefs;
  /** Authentic NPC spawns + roam rectangles from {@code NpcLocs.json}; surfaced via
   *  {@code ServerData.npcSpawns()}. */
  private final List<NpcLocs.Spawn> npcLocs;

  /** Lazily-built, per-instance {@link ServerData} projection (see {@link #serverData()}). */
  private volatile ServerData serverData;

  public GameEnvironment(ItemDefs itemDefs, DoorDefs doorDefs, TileDefs tileDefs,
      ObjectDefs objectDefs, NpcDefs npcDefs, CollisionMap collisionMap,
      List<SceneryLocs.Loc> sceneryLocs, List<BoundaryLocs.Loc> boundaryLocs,
      Path ladderTelepointsXml, ExtraDefs extraDefs, List<NpcLocs.Spawn> npcLocs) {
    this.itemDefs = itemDefs;
    this.doorDefs = doorDefs;
    this.tileDefs = tileDefs;
    this.objectDefs = objectDefs;
    this.npcDefs = npcDefs;
    this.collisionMap = collisionMap;
    this.sceneryLocs = sceneryLocs;
    this.boundaryLocs = boundaryLocs;
    this.ladderTelepointsXml = ladderTelepointsXml;
    this.extraDefs = extraDefs;
    this.npcLocs = npcLocs;
  }

  public ItemDefs itemDefs() {
    return itemDefs;
  }

  public DoorDefs doorDefs() {
    return doorDefs;
  }

  public TileDefs tileDefs() {
    return tileDefs;
  }

  public ObjectDefs objectDefs() {
    return objectDefs;
  }

  public NpcDefs npcDefs() {
    return npcDefs;
  }

  public CollisionMap collisionMap() {
    return collisionMap;
  }

  public List<SceneryLocs.Loc> sceneryLocs() {
    return sceneryLocs;
  }

  public List<BoundaryLocs.Loc> boundaryLocs() {
    return boundaryLocs;
  }

  public Path ladderTelepointsXml() {
    return ladderTelepointsXml;
  }

  public ExtraDefs extraDefs() {
    return extraDefs;
  }

  public List<NpcLocs.Spawn> npcLocs() {
    return npcLocs;
  }

  /**
   * The single {@link ServerData} projection shared by every consumer of this env (a stable
   * identity, so the path planner's danger-field cache doesn't thrash). {@code null} when this env
   * carries no {@code extraDefs} (test envs exercising only collision). Built lazily, once, and
   * cached on the instance — GC'd with it (no static registry).
   */
  public ServerData serverData() {
    if (extraDefs == null) {
      return null;
    }
    ServerData sd = serverData;
    if (sd == null) {
      synchronized (this) {
        sd = serverData;
        if (sd == null) {
          sd = new ServerDataImpl(itemDefs, npcDefs, extraDefs, npcLocs);
          serverData = sd;
        }
      }
    }
    return sd;
  }

  /**
   * Load every def + the collision map, sourcing the landscape from the server's own JAG map
   * archives ({@code <conf>/data/maps/maps}{@value LandscapeSource#AUTHENTIC_MAP_REV}{@code .jag})
   * — the dataset a stock (Uranium) server itself paths against, so collision built this way
   * matches the server's. Fails loudly if those archives are absent; it will never quietly fall
   * back to a different landscape.
   */
  public static GameEnvironment loadFromJag(ServerConf conf, DoorOverrides overrides)
      throws IOException {
    return load(conf, WorldProfile.authentic(), overrides);
  }

  /**
   * Load the world named by a {@link WorldProfile} — its landscape, its locs file set, its defs.
   * This is the entry point for anything that must work on more than the authentic world:
   *
   * <pre>{@code
   * GameEnvironment cabbage = GameEnvironment.load(
   *     conf, WorldProfile.fromConf(conf, "rsccabbage"), DoorOverrides.NONE);
   * }</pre>
   */
  public static GameEnvironment load(ServerConf conf, WorldProfile world, DoorOverrides overrides)
      throws IOException {
    try (LandscapeSource landscape = world.openLandscape(conf)) {
      return load(conf, world, landscape, overrides);
    }
  }

  /**
   * Load every def + the collision map, sourcing the landscape from an {@code .orsc} ZIP — the
   * custom-server hook. Note the {@code .orsc} repack is a distinct dataset from the JAG archives
   * (it carries sectors {@code maps64} does not), so collision built from it will diverge from a
   * stock server's; see {@link LandscapeSource}.
   */
  public static GameEnvironment loadFromOrsc(ServerConf conf, Path orscFile,
      DoorOverrides overrides) throws IOException {
    try (LandscapeSource landscape = LandscapeSource.fromOrsc(orscFile)) {
      return load(conf, WorldProfile.authentic(), landscape, overrides);
    }
  }

  /**
   * Load every def directly from the server checkout's {@code conf/server} tree — the authoritative
   * source (no local copies) — for the world a {@link WorldProfile} names, and the collision map
   * from the landscape you hand in.
   *
   * <p>Use {@link #load(ServerConf, WorldProfile, DoorOverrides)} unless you already hold an open
   * source (e.g. a bake that also renders terrain from it, or one resolved via
   * {@link LandscapeSource#resolve(ServerConf)}). The landscape is not closed here — the caller
   * owns it — and it is <em>not</em> checked against the profile, so passing a landscape the
   * profile would not have chosen is allowed and is exactly how the bake honours
   * {@code -Dopenrsc.landscape}.
   *
   * @param conf      resolved server conf tree (see {@link ServerConf}).
   * @param world     which world's locs/defs to load (see {@link WorldProfile}).
   * @param landscape where landscape tiles come from (see {@link LandscapeSource}).
   * @param overrides door-override seam threaded into the collision map (see
   *                  {@link DoorOverrides}).
   */
  public static GameEnvironment load(ServerConf conf, WorldProfile world,
      LandscapeSource landscape, DoorOverrides overrides) throws IOException {
    long t0 = System.nanoTime();
    LOG.info("Loading game data from server conf tree {} for world {}", conf.root(), world);

    ItemDefs itemDefs = ItemDefs.load(conf.defs().resolve("ItemDefs.json"),
        conf.defs().resolve("ItemDefsCustom.json"));
    LOG.info("Loaded {} item defs", itemDefs.size());

    DoorDefs doorDefs = DoorDefs.loadXml(conf.defs().resolve("DoorDef.xml"));
    TileDefs tileDefs = TileDefs.loadXml(conf.defs().resolve("TileDef.xml"));
    ObjectDefs objectDefs = ObjectDefs.loadXml(conf.defs().resolve("GameObjectDef.xml"));

    // Locs come as an ordered file SET chosen by the world's flags — the base file plus whatever
    // its features add (Discontinued on Uranium; Runecraft/Harvesting/CustomQuest/… on Cabbage).
    // Later files append to earlier ones, exactly as WorldPopulator does.
    var sceneryLocs = new ArrayList<SceneryLocs.Loc>();
    for (Path p : world.sceneryLocs(conf)) {
      sceneryLocs.addAll(SceneryLocs.load(p));
    }
    var boundaryLocs = new ArrayList<BoundaryLocs.Loc>();
    for (Path p : world.boundaryLocs(conf)) {
      boundaryLocs.addAll(BoundaryLocs.load(p));
    }

    // NpcDefs + NpcDefsCustom appended, no Patch18 — see NpcDefs javadoc. Drives the walker's
    // blocked-tile mask under the server's npc_blocking=2 rule.
    NpcDefs npcDefs = NpcDefs.load(conf.defs().resolve("NpcDefs.json"),
        conf.defs().resolve("NpcDefsCustom.json"));
    LOG.info("Loaded {} npc defs", npcDefs.size());

    // NPC spawns + roam rectangles, then the world's post-load spawn fixups (seasonal-event
    // removals / the bunny relocation). Surfaced via ServerData.npcSpawns().
    var loadedNpcLocs = new ArrayList<NpcLocs.Spawn>();
    for (Path p : world.npcLocs(conf)) {
      loadedNpcLocs.addAll(NpcLocs.load(p));
    }
    var npcLocs = world.applyNpcFixups(loadedNpcLocs);
    LOG.info("Loaded {} npc spawns", npcLocs.size());

    Path telePointsXml = conf.extras().resolve("ObjectTelePoints.xml");

    var extraDefs = ExtraDefs.load(conf);
    LOG.info("Loaded skill extras: {}", extraDefs.summary());

    LOG.info("Loading collision from {}", landscape.describe());
    CollisionMap collisionMap = CollisionMap.load(landscape, doorDefs, tileDefs,
        sceneryLocs, objectDefs, boundaryLocs, overrides);
    LOG.info("Loaded collision map ({}x{}) — {} scenery, {} boundary locs in {}ms",
        collisionMap.width(), collisionMap.height(),
        sceneryLocs.size(), boundaryLocs.size(),
        (System.nanoTime() - t0) / 1_000_000);

    return new GameEnvironment(
        itemDefs, doorDefs, tileDefs, objectDefs, npcDefs,
        collisionMap, List.copyOf(sceneryLocs),
        List.copyOf(boundaryLocs), telePointsXml, extraDefs, List.copyOf(npcLocs));
  }
}
