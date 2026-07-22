package openrsc.map;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import openrsc.gamedata.runtime.GameEnvironment;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.world.CollisionMap;
import openrsc.map.render.MapGeoJsonExporter;
import openrsc.map.render.MapPreview;
import openrsc.map.render.TerrainRenderer;
import openrsc.map.render.WallsRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standalone entry point: load the OpenRSC server's game-data tree into a {@link GameEnvironment} and
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
 *   <li>{@code floor-f.light.png}   — base terrain colour raster (full saturation, brighter)</li>
 *   <li>{@code floor-f.dim.png}     — base terrain washed out (the stock-minimap shading; light+walls
 *       vs dim+walls is the same layer stack, just the two colour treatments). Both are emitted so
 *       the client picks / composites whichever it wants.</li>
 *   <li>{@code floor-f.walls.png}   — wall + diagonal outlines only (transparent PNG)</li>
 *   <li>{@code floor-f.blocked.png} — impassable-tile fill only (transparent PNG); a pathfinder aid,
 *       kept off the minimap look so it can be toggled independently of the walls</li>
 *   <li>{@code floor-f.geojson}     — door + NPC-spawn feature layer (no transports; this module
 *       loads no path planner)</li>
 * </ul>
 *
 * <p>Pass {@code -Dmap.scale=N} to bake every layer at {@code N}× resolution (default 1 = the native
 * minimap scale). All four rasters scale together so they stay in registration; the terrain is flat
 * per tile, so a higher scale enlarges the blocks, it does not add sub-tile detail.
 *
 * <p>By default (opt out with {@code -Dmap.preview=false}) it also writes, per floor, flattened
 * full-res maps + thumbnails for both terrain treatments — {@code floor-f.dim.preview.png} /
 * {@code .dim.thumb.png} and {@code floor-f.light.preview.png} / {@code .light.thumb.png} (terrain +
 * blocked fill + wall outlines, cropped to the map's extent) — the showcase images the README /
 * published site embed.
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
    GameEnvironment env = GameEnvironment.loadDefault(conf);

    // -Dmap.scale=N bakes every layer at N× the native px/tile (default 1 = the current output).
    int mult = Math.max(1, Integer.getInteger("map.scale", 1));
    // On by default: also write a flattened light+walls map per floor plus a small thumbnail (the
    // images the README / site showcase embeds). Opt out with -Dmap.preview=false.
    boolean preview = Boolean.parseBoolean(System.getProperty("map.preview", "true"));
    int thumbWidth = Integer.getInteger("map.thumbWidth", 480);

    for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
      // Base terrain, two colour treatments — full saturation (light; the brighter web-map look) and
      // washed out (dim; the stock-minimap shading). Both are emitted so the client picks either.
      BufferedImage lightImg =
          TerrainRenderer.render(floor, env.collisionMap(), env.tileDefs(), false, mult);
      byte[] light = MapPreview.png(lightImg);
      Path lightPath = outDir.resolve("floor-" + floor + ".light.png");
      Files.write(lightPath, light);

      BufferedImage dimImg =
          TerrainRenderer.render(floor, env.collisionMap(), env.tileDefs(), true, mult);
      byte[] dim = MapPreview.png(dimImg);
      Path dimPath = outDir.resolve("floor-" + floor + ".dim.png");
      Files.write(dimPath, dim);

      // Walls (outlines) and blocked-tile fill as SEPARATE layers so they toggle independently:
      // base+walls is the minimap; blocked is the pathfinder aid.
      BufferedImage wallsImg =
          WallsRenderer.render(floor, env.collisionMap(), WallsRenderer.Part.LINES, mult);
      byte[] walls = MapPreview.png(wallsImg);
      Path wallsPath = outDir.resolve("floor-" + floor + ".walls.png");
      Files.write(wallsPath, walls);

      BufferedImage blockedImg =
          WallsRenderer.render(floor, env.collisionMap(), WallsRenderer.Part.FILL, mult);
      byte[] blocked = MapPreview.png(blockedImg);
      Path blockedPath = outDir.resolve("floor-" + floor + ".blocked.png");
      Files.write(blockedPath, blocked);

      // Optional showcase: the full-res in-game-style map — terrain + blocked-tile fill + wall
      // outlines flattened and cropped to the map's extent — plus a thumbnail linking to it. Baked
      // for both terrain treatments (dim = the minimap look, light = brighter) so the site offers
      // either; the walls + blocked overlays are shared.
      if (preview) {
        writePreview(outDir, floor, "dim", dimImg, blockedImg, wallsImg, thumbWidth);
        writePreview(outDir, floor, "light", lightImg, blockedImg, wallsImg, thumbWidth);
      }

      JsonNode geo = MapGeoJsonExporter.buildFloor(
          floor, env.boundaryLocs(), env.doorDefs(),
          env.sceneryLocs(), env.objectDefs(),
          env.npcLocs(), env.npcDefs());
      Path geoPath = outDir.resolve("floor-" + floor + ".geojson");
      Files.write(geoPath, JSON.writeValueAsBytes(geo));

      LOG.info("Floor {} -> {} ({}b), {} (dim {}b), {} (walls {}b), {} (blocked {}b), {}", floor,
          lightPath.getFileName(), light.length,
          dimPath.getFileName(), dim.length,
          wallsPath.getFileName(), walls.length,
          blockedPath.getFileName(), blocked.length, geoPath.getFileName());
    }

    LOG.info("Done. Wrote {} floors ({} px/tile, {}× scale) to {}",
        CollisionMap.FLOOR_COUNT, TerrainRenderer.SCALE * mult, mult, outDir.toAbsolutePath());
  }

  /**
   * Flatten {@code base} terrain + the {@code blocked} fill + the {@code walls} outlines into one
   * cropped full-resolution map and a {@code thumbWidth}-wide thumbnail, written as
   * {@code floor-<floor>.<variant>.preview.png} / {@code .thumb.png}.
   */
  private static void writePreview(Path outDir, int floor, String variant, BufferedImage base,
      BufferedImage blocked, BufferedImage walls, int thumbWidth) throws IOException {
    BufferedImage map = MapPreview.cropToContent(MapPreview.flatten(base, blocked, walls));
    Files.write(outDir.resolve("floor-" + floor + "." + variant + ".preview.png"),
        MapPreview.png(map));
    Files.write(outDir.resolve("floor-" + floor + "." + variant + ".thumb.png"),
        MapPreview.png(MapPreview.scaleToWidth(map, thumbWidth)));
  }
}
