package openrsc.bot.core.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.ServerData;
import openrsc.gamedata.BoundaryLocs;
import openrsc.gamedata.ServerConf;
import openrsc.bot.core.defs.DoorDefs;
import openrsc.gamedata.ItemDefs;
import openrsc.gamedata.NpcDefs;
import openrsc.gamedata.NpcLocs;
import openrsc.bot.core.defs.ObjectDefs;
import openrsc.gamedata.SceneryLocs;
import openrsc.bot.core.defs.TileDefs;
import openrsc.bot.core.defs.extras.ExtraDefs;
import openrsc.bot.core.world.CollisionMap;
import openrsc.gamedata.jag.JagLandscape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bundles all the world-derived data an {@link AccountLoop} needs to launch a bot: defs, the
 * collision map, and the raw inputs (scenery list, ladder telepoint XML) that a script-side path
 * planner can consume.
 *
 * <p>Transports and {@link openrsc.bot.api.PathPlanner} construction no longer
 * live here — they move into the scripts module so they hot-reload with the rest of script
 * behavior. {@link AccountLoop} reflects on {@code openrsc.bot.scripts.path.PathfinderFactory} via
 * the script classloader after every reload and re-installs a fresh planner.
 *
 * <p>Loading is moderately expensive (collision map alone is a few seconds
 * cold). Cache the result and reuse across accounts / tests.
 */
public record BotEnvironment(
    ItemDefs itemDefs,
    DoorDefs doorDefs,
    TileDefs tileDefs,
    ObjectDefs objectDefs,
    NpcDefs npcDefs,
    CollisionMap collisionMap,
    List<SceneryLocs.Loc> sceneryLocs,
    /** Static boundary locs (walls, fences, doors). Retained so the
     *  web UI's GeoJSON exporter can render door points; collision
     *  has already consumed them into {@link #collisionMap}. */
    List<BoundaryLocs.Loc> boundaryLocs,
    Path ladderTelepointsXml,
    /** Skill-content tables from {@code defs/extras/*.xml} — surfaced to
     *  scripts as {@link openrsc.bot.api.ServerData} via
     *  {@code Bot.serverData()}. */
    ExtraDefs extraDefs,
    /** Authentic NPC spawns + roam rectangles from {@code NpcLocs.json}; surfaced
     *  via {@code ServerData.npcSpawns()} so callers can reason about where
     *  aggressive NPCs roam (e.g. wilderness-avoidance tooling). */
    List<NpcLocs.Spawn> npcLocs
) {

  private static final Logger LOG = LoggerFactory.getLogger(BotEnvironment.class);

  /**
   * {@code based_map_data} revision the authentic (Uranium) server uses. Selects
   * {@code maps{rev}.jag/.mem} + {@code land{rev}.jag/.mem}.
   */
  private static final int AUTHENTIC_MAP_REV = 64;

  /**
   * One shared {@link ServerData} projection per env instance. {@code ServerDataImpl} is a pure,
   * immutable projection of this env's defs, so every bot can share a single instance — and must, or
   * the planner's per-(level,weight) danger-field cache thrashes: it keys cache invalidation on
   * {@code ServerData} identity, so handing each of a 100+ bot swarm its own equivalent-but-distinct
   * instance made every pathfind rebuild the field (re-running the danger-aware cluster Dijkstra). Keyed
   * by env identity (cheap, no deep record hashCode); envs are singletons in practice, so this never grows.
   */
  private static final Map<BotEnvironment, ServerData> SHARED_SERVER_DATA =
      Collections.synchronizedMap(new IdentityHashMap<>());

  /**
   * The single {@link ServerData} projection shared by every bot of this env (and the planner's
   * danger-field warm-up), so {@code Bot.serverData()} returns a stable identity swarm-wide. Returns
   * {@code null} when this env carries no {@code extraDefs} (test envs exercising only collision),
   * matching the prior per-bot guard. Built lazily, once per env.
   */
  public ServerData serverData() {
    if (extraDefs == null) {
      return null;
    }
    return SHARED_SERVER_DATA.computeIfAbsent(this,
        e -> new ServerDataImpl(e.itemDefs, e.npcDefs, e.extraDefs, e.npcLocs));
  }

  /**
   * Load every def + the collision map from the server conf tree, using the server's own
   * {@code Authentic_Landscape.orsc} as the fallback landscape. See
   * {@link #loadDefault(ServerConf, Path)}.
   */
  public static BotEnvironment loadDefault(ServerConf conf) throws IOException {
    return loadDefault(conf, conf.data().resolve("Authentic_Landscape.orsc"));
  }

  /**
   * Load every def + the collision map directly from the server checkout's {@code conf/server} tree
   * — the authoritative source (no local copies).
   *
   * @param conf          resolved server conf tree (see {@link ServerConf}).
   * @param landscapeFile landscape ZIP used only when the JAG map archives are absent — the hook
   *                      for custom-server landscapes.
   */
  public static BotEnvironment loadDefault(ServerConf conf, Path landscapeFile) throws IOException {
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
    // (WorldPopulator.loadCustomLocs:209-212). Adds back a few entries
    // removed from base SceneryLocs.json that the server still uses for
    // collision.
    Path discPath = conf.locs().resolve("SceneryLocsDiscontinued.json");
    if (Files.exists(discPath)) {
      sceneryLocs.addAll(SceneryLocs.load(discPath));
    }

    var boundaryLocs = BoundaryLocs.load(conf.locs().resolve("BoundaryLocs.json"));

    // NpcDefs + NpcDefsCustom appended, no Patch18 — see NpcDefs javadoc.
    // Drives the walker's blocked-tile mask under the server's
    // npc_blocking=2 rule.
    NpcDefs npcDefs = NpcDefs.load(conf.defs().resolve("NpcDefs.json"),
        conf.defs().resolve("NpcDefsCustom.json"));
    LOG.info("Loaded {} npc defs", npcDefs.size());

    // Authentic NPC spawns + roam rectangles. based_map_data >= 28 (Uranium = 64) ⇒ the server
    // loads base NpcLocs.json (not the *14/*27 variants); on a members world the F2P filters are
    // skipped, so the whole file spawns. Surfaced via ServerData.npcSpawns().
    var npcLocs = NpcLocs.load(conf.locs().resolve("NpcLocs.json"));
    LOG.info("Loaded {} npc spawns", npcLocs.size());

    Path telePointsXml = conf.extras().resolve("ObjectTelePoints.xml");

    var extraDefs = ExtraDefs.load(conf);
    LOG.info("Loaded skill extras: {}", extraDefs.summary());

    // Collision source precedence mirrors the server's WorldLoader: when
    // the classic JAG map archives (maps{rev}.jag/.mem) are present they
    // are authoritative (the server paths against them when
    // based_map_data >= 28; Uranium = 64). The .orsc repack is the
    // fallback — and the hook for custom-server landscapes. Reading the
    // wrong source silently diverges the bot's collision from the server's
    // (e.g. the .orsc carried upper-floor void sectors maps64 omits, so
    // the bot walked F1 void the server blocks).
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
            sceneryLocs, objectDefs, boundaryLocs);
      }
    } else {
      LOG.info("Loading collision from .orsc landscape {} (JAG maps absent — custom/fallback)",
          landscapeFile);
      collisionMap = CollisionMap.load(landscapeFile, doorDefs, tileDefs,
          sceneryLocs, objectDefs, boundaryLocs);
    }
    LOG.info("Loaded collision map ({}x{}) — {} scenery, {} boundary locs in {}ms",
        collisionMap.width(), collisionMap.height(),
        sceneryLocs.size(), boundaryLocs.size(),
        (System.nanoTime() - t0) / 1_000_000);

    return new BotEnvironment(
        itemDefs, doorDefs, tileDefs, objectDefs, npcDefs,
        collisionMap, List.copyOf(sceneryLocs),
        List.copyOf(boundaryLocs), telePointsXml, extraDefs, List.copyOf(npcLocs));
  }
}
