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
import openrsc.gamedata.api.ServerData;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.DoorOverrides;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.TileDefs;
import openrsc.gamedata.defs.extras.ExtraDefs;
import openrsc.gamedata.jag.JagLandscape;
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

  /**
   * {@code based_map_data} revision the authentic (Uranium) server uses. Selects
   * {@code maps{rev}.jag/.mem} + {@code land{rev}.jag/.mem}.
   */
  private static final int AUTHENTIC_MAP_REV = 64;

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
   * Load every def + the collision map from the server conf tree, using the server's own
   * {@code Authentic_Landscape.orsc} as the fallback landscape and generic door handling (no
   * overrides). See {@link #loadDefault(ServerConf, Path, DoorOverrides)}.
   */
  public static GameEnvironment loadDefault(ServerConf conf) throws IOException {
    return loadDefault(conf, DoorOverrides.NONE);
  }

  /** As {@link #loadDefault(ServerConf)} but with a door-override seam (the bot passes a
   *  SpecialDoor-backed impl; renderers pass {@link DoorOverrides#NONE}). */
  public static GameEnvironment loadDefault(ServerConf conf, DoorOverrides overrides)
      throws IOException {
    return loadDefault(conf, conf.data().resolve("Authentic_Landscape.orsc"), overrides);
  }

  /**
   * Load every def + the collision map directly from the server checkout's {@code conf/server} tree
   * — the authoritative source (no local copies).
   *
   * @param conf          resolved server conf tree (see {@link ServerConf}).
   * @param landscapeFile landscape ZIP used only when the JAG map archives are absent — the hook
   *                      for custom-server landscapes.
   * @param overrides     door-override seam threaded into the collision map (see
   *                      {@link DoorOverrides}).
   */
  public static GameEnvironment loadDefault(ServerConf conf, Path landscapeFile,
      DoorOverrides overrides) throws IOException {
    long t0 = System.nanoTime();
    LOG.info("Loading game data from server conf tree {}", conf.root());

    ItemDefs itemDefs = ItemDefs.load(conf.defs().resolve("ItemDefs.json"),
        conf.defs().resolve("ItemDefsCustom.json"));
    LOG.info("Loaded {} item defs", itemDefs.size());

    DoorDefs doorDefs = DoorDefs.loadXml(conf.defs().resolve("DoorDef.xml"));
    TileDefs tileDefs = TileDefs.loadXml(conf.defs().resolve("TileDef.xml"));
    ObjectDefs objectDefs = ObjectDefs.loadXml(conf.defs().resolve("GameObjectDef.xml"));

    var sceneryLocs = new ArrayList<>(SceneryLocs.load(conf.locs().resolve("SceneryLocs.json")));
    // Uranium loads SceneryLocsDiscontinued.json when WANT_FIXED_BROKEN_MECHANICS=true
    // (WorldPopulator.loadCustomLocs:209-212). Adds back a few entries removed from base
    // SceneryLocs.json that the server still uses for collision.
    Path discPath = conf.locs().resolve("SceneryLocsDiscontinued.json");
    if (Files.exists(discPath)) {
      sceneryLocs.addAll(SceneryLocs.load(discPath));
    }

    var boundaryLocs = BoundaryLocs.load(conf.locs().resolve("BoundaryLocs.json"));

    // NpcDefs + NpcDefsCustom appended, no Patch18 — see NpcDefs javadoc. Drives the walker's
    // blocked-tile mask under the server's npc_blocking=2 rule.
    NpcDefs npcDefs = NpcDefs.load(conf.defs().resolve("NpcDefs.json"),
        conf.defs().resolve("NpcDefsCustom.json"));
    LOG.info("Loaded {} npc defs", npcDefs.size());

    // Authentic NPC spawns + roam rectangles. based_map_data >= 28 (Uranium = 64) ⇒ the server
    // loads base NpcLocs.json; on a members world the F2P filters are skipped, so the whole file
    // spawns. Surfaced via ServerData.npcSpawns().
    var npcLocs = NpcLocs.load(conf.locs().resolve("NpcLocs.json"));
    LOG.info("Loaded {} npc spawns", npcLocs.size());

    Path telePointsXml = conf.extras().resolve("ObjectTelePoints.xml");

    var extraDefs = ExtraDefs.load(conf);
    LOG.info("Loaded skill extras: {}", extraDefs.summary());

    // Collision source precedence mirrors the server's WorldLoader: when the classic JAG map
    // archives (maps{rev}.jag/.mem) are present they are authoritative (the server paths against
    // them when based_map_data >= 28; Uranium = 64). The .orsc repack is the fallback — and the
    // hook for custom-server landscapes.
    Path mapsDir = conf.data().resolve("maps");
    JagLandscape jag = null;
    try {
      jag = JagLandscape.open(mapsDir, AUTHENTIC_MAP_REV, true);
    } catch (IOException e) {
      LOG.warn("Failed to open JAG landscape under {} — falling back to .orsc: {}", mapsDir,
          e.toString());
    }
    CollisionMap collisionMap;
    if (jag != null) {
      LOG.info("Loading collision from JAG maps{} under {} (server-authentic source)",
          AUTHENTIC_MAP_REV, mapsDir);
      try (var landscape = jag) {
        collisionMap = CollisionMap.loadFromJag(landscape, doorDefs, tileDefs,
            sceneryLocs, objectDefs, boundaryLocs, overrides);
      }
    } else {
      LOG.info("Loading collision from .orsc landscape {} (JAG maps absent — custom/fallback)",
          landscapeFile);
      collisionMap = CollisionMap.load(landscapeFile, doorDefs, tileDefs,
          sceneryLocs, objectDefs, boundaryLocs, overrides);
    }
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
