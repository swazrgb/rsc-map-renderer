package openrsc.maprender.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.probe.TransportLabels;
import openrsc.nav.transports.EngageSpec;

/**
 * Writes the pathfinder's transports per floor, as {@code api/map/floor/<floor>.transports.json}.
 * A transport is listed on each floor that holds one of its entry tiles; one with no entry tile, such
 * as a teleport spell, has no place on a map and is left out.
 *
 * <p>Run alone as {@code TransportsBaker <siteRoot>}, it refuses a site whose mesh was baked for
 * another world, so one site never mixes two worlds.
 */
public final class TransportsBaker {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: TransportsBaker <siteRoot>");
            System.exit(2);
            return;
        }
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        Path siteRoot = Path.of(args[0]);
        String meshWorld = WorldMeshExporter.bakedWorld(siteRoot.resolve("api/world3d"));
        if (meshWorld != null && !meshWorld.equals(world.name())) {
            System.err.println("TransportsBaker: " + siteRoot + " holds a bake of " + meshWorld + ", not "
                    + world.name() + " — pass -D" + WorldProfile.WORLD_PROPERTY + "=" + meshWorld
                    + " or bake the whole site");
            System.exit(1);
            return;
        }
        ServerData data;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            data = ServerData.load(conf, world, landscape);
        }
        export(data, siteRoot);
    }

    /** One floor's file; {@code world} is the {@link WorldProfile#name()} it was baked for. */
    public record FloorFile(String world, List<FloorTransport> transports) {}

    /**
     * One transport on one floor, in floor-local tiles. {@code anchor} (its scenery) and
     * {@code boundary} (its wall object) are {@code null} unless one stands on this floor. The landing
     * {@code dx, dz} on {@code dfloor} is the one from the first entry here; {@code perEntry} says
     * the landing differs by entry tile, as a ladder's does.
     */
    public record FloorTransport(
            String name,
            String label,
            String req,
            Tile anchor,
            Wall boundary,
            List<Tile> entries,
            int dx,
            int dz,
            int dfloor,
            boolean perEntry) {}

    /** A wall object's tile and edge; {@code dir} is negative when any edge matches. */
    public record Wall(int x, int z, int dir) {}

    public record Tile(int x, int z) {}

    public static void export(ServerData data, Path siteRoot) throws IOException {
        long t0 = System.nanoTime();
        List<Transport> transports = Pathfinder.load(data, NavCaches.none()).transports();
        Path dir = siteRoot.resolve("api/map/floor");
        Files.createDirectories(dir);
        int written = 0;
        for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
            List<FloorTransport> out = floor(transports, floor, data);
            written += out.size();
            BakeJson.MAPPER.writeValue(
                    dir.resolve(floor + ".transports.json").toFile(),
                    new FloorFile(data.world().name(), out));
        }
        System.out.printf("transports: %d placed in %d ms -> %s%n", written, (System.nanoTime() - t0) / 1_000_000, dir);
    }

    static List<FloorTransport> floor(List<Transport> transports, int floor, ServerData defs) {
        int base = floor * CollisionMap.FLOOR_HEIGHT;
        int end = base + CollisionMap.FLOOR_HEIGHT;
        Map<Integer, FloorTransport> byTransport = new LinkedHashMap<>();
        for (int tid = 0; tid < transports.size(); tid++) {
            Transport t = transports.get(tid);
            List<Point> entries = t.entries();
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            for (Point e : entries) {
                if (e.y() < base || e.y() >= end) {
                    continue;
                }
                FloorTransport g = byTransport.get(tid);
                if (g == null) {
                    Point dest = t.destinationFrom(e);
                    int dfloor = dest.y() / CollisionMap.FLOOR_HEIGHT;
                    EngageSpec.Target.Boundary b = t.boundary();
                    Tile wallTile = b == null ? null : onFloor(Point.of(b.x(), b.y()), base);
                    g = new FloorTransport(
                            t.name(),
                            TransportLabels.of(t, defs),
                            Transport.requirementText(t, defs.itemDefs()),
                            onFloor(t.sceneryAnchor(), base),
                            wallTile == null ? null : new Wall(wallTile.x(), wallTile.z(), b.dir()),
                            new ArrayList<>(),
                            dest.x(),
                            dest.y() - dfloor * CollisionMap.FLOOR_HEIGHT,
                            dfloor,
                            t.landingDependsOnEntry());
                    byTransport.put(tid, g);
                }
                g.entries().add(new Tile(e.x(), e.y() - base));
            }
        }
        return new ArrayList<>(byTransport.values());
    }

    private static Tile onFloor(Point p, int base) {
        return p != null && p.y() >= base && p.y() < base + CollisionMap.FLOOR_HEIGHT
                ? new Tile(p.x(), p.y() - base)
                : null;
    }

    private TransportsBaker() {}
}
