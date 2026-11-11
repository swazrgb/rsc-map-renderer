package openrsc.livemap.assets;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import openrsc.gamedata.ServerData;
import openrsc.maprender.bake.SceneryAtlasBaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the 2D map's scenery sprite atlas, baked into {@link WorldAssets#bakeDir} on a background
 * thread once, and 503 until it is.
 */
@RestController
@RequestMapping("/api/map")
public class SceneryController {

    private static final Logger LOG = LoggerFactory.getLogger(SceneryController.class);

    private final ServerData env;
    private final WorldAssets assets;
    private final Path atlasDir;

    public SceneryController(ServerData env, WorldAssets assets) {
        this.env = env;
        this.assets = assets;
        this.atlasDir = assets.bakeDir().resolve("object-sprites");
    }

    @PostConstruct
    void bakeInBackground() {
        if (SceneryAtlasBaker.isBaked(atlasDir) || !assets.canBake(LOG, "scenery atlas")) {
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        SceneryAtlasBaker.bake(assets.clientCache().toString(), env.sceneryLocs(), atlasDir);
                    } catch (Throwable e) {
                        LOG.error("scenery atlas bake failed", e);
                    }
                },
                "scenery-atlas-bake");
        t.setDaemon(true);
        t.start();
    }

    @GetMapping(value = "/scenery-atlas.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> atlasPng() throws Exception {
        return serveBaked(
                SceneryAtlasBaker.atlasPng(atlasDir), MediaType.IMAGE_PNG, CacheControl.maxAge(Duration.ofDays(7)));
    }

    @GetMapping(value = "/scenery-atlas.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> atlasJson() throws Exception {
        return serveBaked(SceneryAtlasBaker.atlasJson(atlasDir), MediaType.APPLICATION_JSON, CacheControl.noCache());
    }

    private ResponseEntity<byte[]> serveBaked(Path file, MediaType type, CacheControl cache) throws Exception {
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5")
                    .build();
        }
        return ResponseEntity.ok().contentType(type).cacheControl(cache).body(Files.readAllBytes(file));
    }
}
