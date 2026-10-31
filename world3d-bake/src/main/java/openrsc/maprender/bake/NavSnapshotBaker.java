package openrsc.maprender.bake;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerDataSnapshot;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;

/**
 * Writes the gzipped {@link ServerDataSnapshot} the in-page route planner builds its pathfinder
 * from. Run alone as {@code NavSnapshotBaker <siteRoot>}.
 */
public final class NavSnapshotBaker {

    /** The snapshot's path, relative to the site root. */
    public static final String SNAPSHOT = "api/nav/world.snap.gz";

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: NavSnapshotBaker <siteRoot>");
            System.exit(2);
            return;
        }
        ServerConf conf = ServerConf.resolve();
        export(conf, WorldProfile.resolve(conf), Path.of(args[0]));
    }

    /** Returns the server data the snapshot was written from. */
    public static ServerData export(ServerConf conf, WorldProfile world, Path siteRoot) throws IOException {
        long t0 = System.nanoTime();
        ServerData data;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            data = ServerData.load(conf, world, landscape);
        }
        byte[] bytes = ServerDataSnapshot.write(conf, data);
        Path out = siteRoot.resolve(SNAPSHOT);
        Files.createDirectories(out.getParent());
        try (OutputStream gz = new GZIPOutputStream(Files.newOutputStream(out), 1 << 16)) {
            gz.write(bytes);
        }
        System.out.printf(
                "nav snapshot (%s): %d KB, %d KB gzipped, in %d ms -> %s%n",
                world.name(), bytes.length >> 10, Files.size(out) >> 10, (System.nanoTime() - t0) / 1_000_000, out);
        return data;
    }

    private NavSnapshotBaker() {}
}
