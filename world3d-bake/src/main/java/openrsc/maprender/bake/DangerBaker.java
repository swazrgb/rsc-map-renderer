package openrsc.maprender.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Pathfinder;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.probe.NavDangerExport;

/**
 * Bakes the pathfinder's aggressive-NPC danger per tile, one JSON file per floor at
 * {@code <siteRoot>/api/map/floor/<floor>.danger.json}. Run alone as {@code DangerBaker <siteRoot>}.
 *
 * <p>The danger is what a player of combat level 0 meets, so every aggressive spawn shows: a static
 * file cannot vary with the viewer's level.
 */
public final class DangerBaker {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: DangerBaker <siteRoot>");
            System.exit(2);
            return;
        }
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        ServerData data;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            data = ServerData.load(conf, world, landscape);
        }
        export(data, Path.of(args[0]));
    }

    public static void export(ServerData data, Path siteRoot) throws IOException {
        long t0 = System.nanoTime();
        NavDangerExport danger = NavDangerExport.build(Pathfinder.load(data, NavCaches.none()), 0);
        Path dir = siteRoot.resolve("api/map/floor");
        Files.createDirectories(dir);
        int tiles = 0;
        for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
            NavDangerExport.FloorTiles t = danger.tiles(floor);
            tiles += t.v().length;
            BakeJson.MAPPER.writeValue(dir.resolve(floor + ".danger.json").toFile(), t);
        }
        System.out.printf("danger zones: %d tiles in %d ms -> %s%n", tiles, (System.nanoTime() - t0) / 1_000_000, dir);
    }

    private DangerBaker() {}
}
