package openrsc.livemap.assets;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.maprender.bake.WorldMeshExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the baked 3D world under {@code /api/world3d}. It bakes into {@link WorldAssets#bakeDir} on a
 * background thread once, when no bake of this format, world and landscape is there yet, and answers
 * 503 until the bake is done.
 */
@RestController
@RequestMapping("/api/world3d")
public class WorldMeshController {

    private static final Logger LOG = LoggerFactory.getLogger(WorldMeshController.class);

    private final Path meshDir;

    private final WorldAssets assets;
    private final ServerData env;

    public WorldMeshController(WorldAssets assets, ServerData env) {
        this.assets = assets;
        this.env = env;
        this.meshDir = assets.bakeDir().resolve("world-mesh");
    }

    @PostConstruct
    void bakeInBackground() {
        WorldProfile world = env.world();
        Path landscapeFile = assets.landscapeFile();
        Thread t = new Thread(
                () -> {
                    try {
                        if (WorldMeshExporter.bakeIsCurrent(meshDir, world, landscapeFile)) {
                            LOG.info(
                                    "world mesh already baked (format v{}, {}) at {}",
                                    WorldMeshExporter.FORMAT_VERSION,
                                    world.name(),
                                    meshDir);
                            return;
                        }
                        if (!assets.canBake(LOG, "3D world")) {
                            return;
                        }
                        try (LandscapeSource landscape = assets.openLandscape(world)) {
                            WorldMeshExporter.export(
                                    assets.clientCache().toString(),
                                    meshDir.toFile(),
                                    msg -> LOG.info("world-mesh bake: {}", msg),
                                    assets.conf(),
                                    world,
                                    landscape,
                                    landscapeFile);
                        }
                    } catch (Throwable e) {
                        LOG.error("world mesh bake failed", e);
                    }
                },
                "world-mesh-bake");
        t.setDaemon(true);
        t.start();
    }

    @GetMapping(value = "/manifest.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> manifest() throws Exception {
        return serve(meshDir.resolve("manifest.json"), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    @GetMapping(value = "/cell/{name:c_p[0-3]_[0-9]+_[0-9]+_[a-z0-9]+}.bin")
    public ResponseEntity<byte[]> cell(@PathVariable("name") String name) throws Exception {
        return serve(
                meshDir.resolve(name + ".bin"),
                MediaType.APPLICATION_OCTET_STREAM,
                CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/objlib.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> objLibIndex() throws Exception {
        return serve(meshDir.resolve("objlib.json"), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    @GetMapping(value = "/objlib.bin")
    public ResponseEntity<byte[]> objLibBlob() throws Exception {
        return serve(
                meshDir.resolve("objlib.bin"),
                MediaType.APPLICATION_OCTET_STREAM,
                CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/doorlib.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> doorLib() throws Exception {
        return serve(meshDir.resolve("doorlib.json"), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    @GetMapping(value = "/npc-atlas.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> npcAtlasIndex() throws Exception {
        return serve(meshDir.resolve("npc-atlas.json"), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    @GetMapping(value = "/npc-atlas.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> npcAtlasPng() throws Exception {
        return serve(meshDir.resolve("npc-atlas.png"), MediaType.IMAGE_PNG, CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/item-atlas.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> itemAtlasIndex() throws Exception {
        return serve(meshDir.resolve("item-atlas.json"), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    @GetMapping(value = "/item-atlas.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> itemAtlasPng() throws Exception {
        return serve(meshDir.resolve("item-atlas.png"), MediaType.IMAGE_PNG, CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/projectile/{n:[0-9]}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> projectilePng(@PathVariable("n") int n) throws Exception {
        return serve(
                meshDir.resolve("projectile-" + n + ".png"),
                MediaType.IMAGE_PNG,
                CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/font-h12b.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> fontAtlasIndex() throws Exception {
        return serve(meshDir.resolve("font-h12b.json"), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    @GetMapping(value = "/font-h12b.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> fontAtlasPng() throws Exception {
        return serve(meshDir.resolve("font-h12b.png"), MediaType.IMAGE_PNG, CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/font-h12b.ttf", produces = "font/ttf")
    public ResponseEntity<byte[]> fontTtf() throws Exception {
        return serve(
                meshDir.resolve("font-h12b.ttf"),
                MediaType.parseMediaType("font/ttf"),
                CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/splat-red.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> splatRedPng() throws Exception {
        return serve(meshDir.resolve("splat-red.png"), MediaType.IMAGE_PNG, CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/splat-blue.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> splatBluePng() throws Exception {
        return serve(meshDir.resolve("splat-blue.png"), MediaType.IMAGE_PNG, CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/player-layers/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> playerLayersIndex() throws Exception {
        return serve(
                meshDir.resolve("player-layers").resolve("index.json"),
                MediaType.APPLICATION_JSON,
                CacheControl.noCache());
    }

    @GetMapping(value = "/player-layers/atlas.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> playerLayersAtlas() throws Exception {
        return serve(
                meshDir.resolve("player-layers").resolve("atlas.png"),
                MediaType.IMAGE_PNG,
                CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/tex/{id:[0-9]+}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> texture(@PathVariable("id") int id) throws Exception {
        return serve(
                meshDir.resolve("tex").resolve("t" + id + ".png"),
                MediaType.IMAGE_PNG,
                CacheControl.maxAge(Duration.ofDays(7)));
    }

    private ResponseEntity<byte[]> serve(Path file, MediaType type, CacheControl cache) throws Exception {
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "10")
                    .build();
        }
        return ResponseEntity.ok().contentType(type).cacheControl(cache).body(Files.readAllBytes(file));
    }
}
