package openrsc.map;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
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
 * Renders every floor of the configured world to image and GeoJSON layers in one directory, given as
 * the only argument (default {@code map-out}). Per floor {@code f} it writes:
 *
 * <ul>
 *   <li>{@code floor-f.light.png} and {@code floor-f.dim.png}: the terrain, full-colour and with the
 *       stock minimap's halved shading ({@link TerrainRenderer}), transparent off the landscape
 *   <li>{@code floor-f.walls.png}: wall and diagonal outlines, transparent elsewhere
 *   <li>{@code floor-f.blocked.png}: the blocked-tile fill, transparent elsewhere
 *   <li>{@code floor-f.geojson}: doors and NPC spawns ({@link MapGeoJsonExporter})
 *   <li>unless {@code -Dmap.preview=false}, {@code floor-f.dim.preview.png}, {@code .dim.thumb.png}
 *       and their {@code light} pair: terrain, fill and outlines flattened and cropped, plus a
 *       thumbnail {@code -Dmap.thumbWidth} px wide (default 480)
 * </ul>
 *
 * <p>{@code -Dmap.scale=N} renders every raster at {@code N} times its native pixels per tile, so the
 * layers stay in register; the terrain is one colour per tile, so it gains size, not detail.
 */
public final class MapRendererMain {

    private static final Logger LOG = LoggerFactory.getLogger(MapRendererMain.class);

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "map-out");
        Files.createDirectories(outDir);

        ServerConf conf = ServerConf.resolve();
        LOG.info("Loading game data from {}", conf.root());
        WorldProfile world = WorldProfile.resolve(conf);
        LOG.info("Rendering world {}", world);
        ServerData env;
        try (LandscapeSource landscape = LandscapeSource.resolve(conf, world)) {
            env = ServerData.load(conf, world, landscape);
        }

        int mult = Math.max(1, Integer.getInteger("map.scale", 1));
        boolean preview = Boolean.parseBoolean(System.getProperty("map.preview", "true"));
        int thumbWidth = Integer.getInteger("map.thumbWidth", 480);

        for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
            BufferedImage lightImg = TerrainRenderer.render(floor, env.collisionMap(), env.tileDefs(), false, mult);
            byte[] light = MapPreview.png(lightImg);
            Path lightPath = outDir.resolve("floor-" + floor + ".light.png");
            Files.write(lightPath, light);

            BufferedImage dimImg = TerrainRenderer.render(floor, env.collisionMap(), env.tileDefs(), true, mult);
            byte[] dim = MapPreview.png(dimImg);
            Path dimPath = outDir.resolve("floor-" + floor + ".dim.png");
            Files.write(dimPath, dim);

            BufferedImage wallsImg = WallsRenderer.render(floor, env.collisionMap(), WallsRenderer.Part.LINES, mult);
            byte[] walls = MapPreview.png(wallsImg);
            Path wallsPath = outDir.resolve("floor-" + floor + ".walls.png");
            Files.write(wallsPath, walls);

            BufferedImage blockedImg = WallsRenderer.render(floor, env.collisionMap(), WallsRenderer.Part.FILL, mult);
            byte[] blocked = MapPreview.png(blockedImg);
            Path blockedPath = outDir.resolve("floor-" + floor + ".blocked.png");
            Files.write(blockedPath, blocked);

            if (preview) {
                writePreview(outDir, floor, "dim", dimImg, blockedImg, wallsImg, thumbWidth);
                writePreview(outDir, floor, "light", lightImg, blockedImg, wallsImg, thumbWidth);
            }

            JsonNode geo = MapGeoJsonExporter.buildFloor(
                    floor,
                    env.boundaryLocs(),
                    env.doorDefs(),
                    env.sceneryLocs(),
                    env.objectDefs(),
                    env.npcLocs(),
                    env.npcDefs());
            Path geoPath = outDir.resolve("floor-" + floor + ".geojson");
            Files.write(geoPath, JSON.writeValueAsBytes(geo));

            LOG.info(
                    "Floor {} -> {} ({}b), {} (dim {}b), {} (walls {}b), {} (blocked {}b), {}",
                    floor,
                    lightPath.getFileName(),
                    light.length,
                    dimPath.getFileName(),
                    dim.length,
                    wallsPath.getFileName(),
                    walls.length,
                    blockedPath.getFileName(),
                    blocked.length,
                    geoPath.getFileName());
        }

        LOG.info(
                "Done. Wrote {} floors ({} px/tile, {}× scale) to {}",
                CollisionMap.FLOOR_COUNT,
                TerrainRenderer.SCALE * mult,
                mult,
                outDir.toAbsolutePath());
    }

    private static void writePreview(
            Path outDir,
            int floor,
            String variant,
            BufferedImage base,
            BufferedImage blocked,
            BufferedImage walls,
            int thumbWidth)
            throws IOException {
        BufferedImage map = MapPreview.cropToContent(MapPreview.flatten(base, blocked, walls));
        Files.write(outDir.resolve("floor-" + floor + "." + variant + ".preview.png"), MapPreview.png(map));
        Files.write(
                outDir.resolve("floor-" + floor + "." + variant + ".thumb.png"),
                MapPreview.png(MapPreview.scaleToWidth(map, thumbWidth)));
    }

    private MapRendererMain() {}
}
