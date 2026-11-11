package openrsc.livemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.livemap.assets.MapNav;
import openrsc.livemap.assets.WorldAssets;
import openrsc.livemap.auth.PasswordAuth;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.live.AnonymousView;
import openrsc.livemap.live.LiveRoster;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

class LiveMapWebTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LiveMap.class)
    static class App {}

    /** Keeps the controllers other tests declare out of the module's scan, as an application has none. */
    static final class SkipTestClasses extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getClassMetadata().getClassName().matches(".*Test(\\$.*)?");
        }
    }

    /** No bots, all shown. */
    static final class NoBots implements LiveRoster, AnonymousView {
        @Override
        public List<String> usernames(boolean authed) {
            return List.of();
        }

        @Override
        public @Nullable BotLiveDto unparked(String username) {
            return null;
        }

        @Override
        public boolean shows(BotLiveDto bot) {
            return true;
        }

        @Override
        public boolean showsDetail(String username) {
            return true;
        }
    }

    @TempDir
    static Path bakeDir;

    private static ConfigurableApplicationContext ctx;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        ServerData env = RealDefs.serverData();
        Pathfinder pathfinder = RealDefs.pathfinder();
        // No client cache: the 3D world and the scenery atlas are not baked.
        WorldAssets assets = new WorldAssets(bakeDir.resolve("no-client-cache"), bakeDir, ServerConf.resolve(), null);
        SpringApplication app = new SpringApplication(App.class);
        app.addInitializers(c -> {
            c.getBeanFactory().registerSingleton("serverData", env);
            c.getBeanFactory().registerSingleton("mapNav", MapNav.of(pathfinder));
            c.getBeanFactory().registerSingleton("worldAssets", assets);
            c.getBeanFactory().registerSingleton("bots", new NoBots());
            c.getBeanFactory().registerSingleton("passwordAuth", new PasswordAuth("map_auth", "map-auth-v1", null));
            c.getBeanFactory().registerSingleton("skipTestClasses", new SkipTestClasses());
        });
        ctx = app.run("--server.port=0", "--server.address=127.0.0.1");
    }

    @AfterAll
    static void stop() {
        if (ctx != null) {
            ctx.close();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheWorldDataAndTheFloors() throws Exception {
        HttpResponse<String> spawns = get("/api/world/npc-spawns.json");
        assertEquals(200, spawns.statusCode());
        assertTrue(spawns.body().startsWith("["), "the spawns are a JSON array");

        HttpResponse<String> floors = get("/api/map/floors");
        assertEquals(200, floors.statusCode());
        assertTrue(floors.body().contains("\"floors\":[0,1,2,3]"), floors.body());

        HttpResponse<String> floor = get("/api/map/floor/0.geojson");
        assertEquals(200, floor.statusCode());
        assertTrue(floor.body().contains("\"kind\":\"transport\""), "the floor carries its transports");
    }

    @Test
    void answersUnavailableWhileTheWorldIsNotBaked() throws Exception {
        assertEquals(503, get("/api/world3d/manifest.json").statusCode());
        assertEquals(503, get("/api/world3d/cell/c_p0_48_47_terrain.bin").statusCode());
        assertEquals(503, get("/api/world3d/tex/1.png").statusCode());
        assertEquals(503, get("/api/world3d/projectile/1.png").statusCode());
    }
}
