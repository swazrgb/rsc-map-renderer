package openrsc.livemap.assets;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.ComponentTiles;
import openrsc.map.render.DangerRenderer;
import openrsc.map.render.MapGeoJsonExporter;
import openrsc.map.render.TerrainRenderer;
import openrsc.map.render.WallsRenderer;
import openrsc.nav.probe.NavDangerExport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Serves the 2D map's per-floor layers under {@code /api/map}: features as GeoJSON, terrain, walls,
 * walkable components and the danger the lowest-level account meets. Each is rendered once and kept.
 */
@RestController
@RequestMapping("/api/map")
public class MapController {

    private final MapNav nav;
    private final ServerData env;
    private final AtomicReferenceArray<JsonNode> floorCache = new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private final AtomicReferenceArray<byte[]> terrainPngCache = new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private final AtomicReferenceArray<byte[]> wallLinesPngCache = new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private final AtomicReferenceArray<byte[]> wallFillPngCache = new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private final AtomicReferenceArray<byte[]> componentsCache = new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private final AtomicReferenceArray<byte[]> dangerPngCache = new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private final AtomicReferenceArray<NavDangerExport.FloorTiles> dangerTilesCache =
            new AtomicReferenceArray<>(CollisionMap.FLOOR_COUNT);
    private volatile NavDangerExport danger;

    public MapController(MapNav nav) {
        this.nav = nav;
        this.env = nav.serverData();
    }

    @PostConstruct
    void warm() {
        for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
            floorCache.set(floor, buildFloor(floor));
            terrainPngCache.set(floor, buildTerrain(floor));
        }
    }

    @GetMapping("/floors")
    public Object floors() {
        List<Integer> floors = new ArrayList<>();
        for (int f = 0; f < CollisionMap.FLOOR_COUNT; f++) {
            floors.add(f);
        }
        return new Floors(floors, CollisionMap.WIDTH, CollisionMap.FLOOR_HEIGHT);
    }

    @GetMapping(value = "/floor/{floor}.geojson", produces = "application/geo+json")
    public ResponseEntity<JsonNode> floor(@PathVariable("floor") int floor) {
        if (floor < 0 || floor >= CollisionMap.FLOOR_COUNT) {
            return ResponseEntity.notFound().build();
        }
        JsonNode fc = floorCache.get(floor);
        if (fc == null) {
            fc = buildFloor(floor);
            floorCache.set(floor, fc);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(fc);
    }

    @GetMapping(value = "/floor/{floor}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> floorPng(@PathVariable("floor") int floor) {
        if (floor < 0 || floor >= CollisionMap.FLOOR_COUNT) {
            return ResponseEntity.notFound().build();
        }
        byte[] png = terrainPngCache.get(floor);
        if (png == null) {
            png = buildTerrain(floor);
            terrainPngCache.set(floor, png);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping(value = "/floor/{floor}.walls-fill.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> floorWallsFillPng(@PathVariable("floor") int floor) {
        return wallsPart(floor, wallFillPngCache, WallsRenderer.Part.FILL);
    }

    @GetMapping(value = "/floor/{floor}.walls-lines.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> floorWallsLinesPng(@PathVariable("floor") int floor) {
        return wallsPart(floor, wallLinesPngCache, WallsRenderer.Part.LINES);
    }

    private ResponseEntity<byte[]> wallsPart(int floor, AtomicReferenceArray<byte[]> cache, WallsRenderer.Part part) {
        if (floor < 0 || floor >= CollisionMap.FLOOR_COUNT) {
            return ResponseEntity.notFound().build();
        }
        byte[] png = cache.get(floor);
        if (png == null) {
            png = WallsRenderer.renderPng(floor, env.collisionMap(), part);
            cache.set(floor, png);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping(value = "/floor/{floor}.components.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> floorComponents(@PathVariable("floor") int floor) {
        if (floor < 0 || floor >= CollisionMap.FLOOR_COUNT) {
            return ResponseEntity.notFound().build();
        }
        byte[] bin = componentsCache.get(floor);
        if (bin == null) {
            bin = ComponentTiles.encode(env.collisionMap(), floor);
            componentsCache.set(floor, bin);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bin);
    }

    @GetMapping(value = "/floor/{floor}.danger.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> floorDangerPng(@PathVariable("floor") int floor) {
        if (floor < 0 || floor >= CollisionMap.FLOOR_COUNT) {
            return ResponseEntity.notFound().build();
        }
        byte[] png = dangerPngCache.get(floor);
        if (png == null) {
            png = DangerRenderer.renderPng(floor, danger());
            dangerPngCache.set(floor, png);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping(value = "/floor/{floor}.danger.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<NavDangerExport.FloorTiles> floorDangerTiles(@PathVariable("floor") int floor) {
        if (floor < 0 || floor >= CollisionMap.FLOOR_COUNT) {
            return ResponseEntity.notFound().build();
        }
        NavDangerExport.FloorTiles tiles = dangerTilesCache.get(floor);
        if (tiles == null) {
            tiles = danger().tiles(floor);
            dangerTilesCache.set(floor, tiles);
        }
        return ResponseEntity.ok(tiles);
    }

    /** The danger map of a level-0 account, as every viewer is shown it. */
    private NavDangerExport danger() {
        NavDangerExport d = danger;
        if (d == null) {
            synchronized (this) {
                d = danger;
                if (d == null) {
                    d = NavDangerExport.build(nav.pathfinder(), 0);
                    danger = d;
                }
            }
        }
        return d;
    }

    private JsonNode buildFloor(int floor) {
        return MapGeoJsonExporter.buildFloor(
                floor,
                env.boundaryLocs(),
                env.doorDefs(),
                env.sceneryLocs(),
                env.objectDefs(),
                nav.transports(),
                env.npcLocs(),
                env.npcDefs(),
                env);
    }

    private byte[] buildTerrain(int floor) {
        return TerrainRenderer.renderPng(floor, env.collisionMap(), env.tileDefs());
    }

    public record Floors(List<Integer> floors, int width, int floorHeight) {}
}
