package openrsc.map;

import java.nio.file.Files;
import java.nio.file.Path;
import openrsc.bot.core.runtime.BotEnvironment;
import openrsc.gamedata.ServerConf;
import openrsc.bot.core.world.CollisionMap;
import openrsc.bot.core.world.cache.MapGeoJsonExporter;
import openrsc.bot.core.world.cache.TerrainRenderer;
import openrsc.bot.core.world.cache.WallsRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standalone entry point: load the OpenRSC server's game-data tree into a {@link BotEnvironment} and
 * bake the world map to PNG — one terrain colour raster plus one walls/diagonals/blocked overlay
 * per floor.
 *
 * <p>No networking, no scripts, no Spring — just the renderers extracted from
 * {@code headless-bot-java/core} driven against the on-disk data files.
 *
 * <h2>Where the data comes from</h2>
 * The server's {@code conf/server} tree is located by {@link ServerConf#resolve()}, which walks up
 * from the working directory looking for {@code <ancestor>/openrsc/server/conf/server}. Running this
 * module from anywhere inside the {@code openrsc} checkout therefore needs no configuration. To
 * point it elsewhere, pass {@code -Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server} or
 * set {@code OPENRSC_SERVER_CONF}.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar target/rsc-map-renderer.jar [outputDir]
 * </pre>
 * {@code outputDir} defaults to {@code ./map-out}. Writes, for each floor {@code f} in
 * {@code 0..FLOOR_COUNT-1}:
 * <ul>
 *   <li>{@code floor-f.png}        — terrain colour raster</li>
 *   <li>{@code floor-f.walls.png}  — walls + diagonals + blocked-tile overlay (transparent PNG)</li>
 *   <li>{@code floor-f.geojson}    — door + NPC-spawn feature layer (no transports; this module
 *       loads no path planner)</li>
 * </ul>
 */
public final class MapRendererMain {

  private static final Logger LOG = LoggerFactory.getLogger(MapRendererMain.class);

  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build();

  private MapRendererMain() {
  }

  public static void main(String[] args) throws Exception {
    Path outDir = Path.of(args.length > 0 ? args[0] : "map-out");
    Files.createDirectories(outDir);

    ServerConf conf = ServerConf.resolve();
    LOG.info("Loading game data from {}", conf.root());
    BotEnvironment env = BotEnvironment.loadDefault(conf);

    for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
      byte[] terrain = TerrainRenderer.renderPng(floor, env.collisionMap(), env.tileDefs());
      Path terrainPath = outDir.resolve("floor-" + floor + ".png");
      Files.write(terrainPath, terrain);

      byte[] walls = WallsRenderer.renderPng(floor, env.collisionMap());
      Path wallsPath = outDir.resolve("floor-" + floor + ".walls.png");
      Files.write(wallsPath, walls);

      JsonNode geo = MapGeoJsonExporter.buildFloor(
          floor, env.boundaryLocs(), env.doorDefs(),
          env.sceneryLocs(), env.objectDefs(),
          env.npcLocs(), env.npcDefs());
      Path geoPath = outDir.resolve("floor-" + floor + ".geojson");
      Files.write(geoPath, JSON.writeValueAsBytes(geo));

      LOG.info("Floor {} -> {} ({} bytes), {} ({} bytes), {}", floor,
          terrainPath.getFileName(), terrain.length,
          wallsPath.getFileName(), walls.length, geoPath.getFileName());
    }

    LOG.info("Done. Wrote {} floors ({} px/tile) to {}",
        CollisionMap.FLOOR_COUNT, TerrainRenderer.SCALE, outDir.toAbsolutePath());
  }
}
