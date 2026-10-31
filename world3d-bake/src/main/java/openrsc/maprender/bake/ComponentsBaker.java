package openrsc.maprender.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.ComponentTiles;

/**
 * Bakes every tile's walking component, one {@link ComponentTiles} file per floor at
 * {@code <siteRoot>/api/map/floor/<floor>.components.bin}. Run alone as
 * {@code ComponentsBaker <siteRoot>}.
 */
public final class ComponentsBaker {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ComponentsBaker <siteRoot>");
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
        Path dir = siteRoot.resolve("api/map/floor");
        Files.createDirectories(dir);
        CollisionMap map = data.collisionMap();
        for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
            Files.write(dir.resolve(floor + ".components.bin"), ComponentTiles.encode(map, floor));
        }
        System.out.printf(
                "walking components: %d in %d ms -> %s%n",
                map.componentCount(), (System.nanoTime() - t0) / 1_000_000, dir);
    }

    private ComponentsBaker() {}
}
