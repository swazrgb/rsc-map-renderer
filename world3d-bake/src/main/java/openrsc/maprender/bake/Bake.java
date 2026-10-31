package openrsc.maprender.bake;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;

/**
 * Bakes the whole static site the viewer runs from: {@code java Bake [clientCacheDir] <outDir>}.
 *
 * <p>Everything lands under {@code <outDir>/api/}, at the paths the viewer fetches, so any static
 * file host can serve it with no backend. The client cache defaults to
 * {@link ServerConf#clientCache()}; the world is the one {@link WorldProfile#resolve} selects.
 */
public final class Bake {

    public static void main(String[] args) throws Exception {
        String cacheDir;
        Path siteRoot;
        if (args.length >= 2) {
            cacheDir = args[0];
            siteRoot = new File(args[1]).toPath();
        } else if (args.length == 1) {
            cacheDir = ServerConf.clientCache().toString();
            siteRoot = new File(args[0]).toPath();
        } else {
            System.err.println("usage: Bake [clientCacheDir] <outDir>");
            System.exit(2);
            return;
        }
        WorldProfile world = WorldProfile.resolve(ServerConf.resolve());
        System.out.println("baking world: " + world);

        Path api = siteRoot.resolve("api").resolve("world3d");
        Files.createDirectories(api);

        WorldMeshExporter.export(cacheDir, api.toFile(), System.out::println, world);

        AuxDataBaker.export(cacheDir, siteRoot, System.out::println, world);

        arrangeStatic(api);
        // The viewer draws game text from the bitmap atlas; it never loads the TrueType face.
        Files.deleteIfExists(api.resolve("font-h12b.ttf"));

        ServerData data = NavSnapshotBaker.export(ServerConf.resolve(), world, siteRoot);

        DangerBaker.export(data, siteRoot);

        ComponentsBaker.export(data, siteRoot);

        TransportsBaker.export(data, siteRoot);
        System.out.println("static site tree ready: " + siteRoot.resolve("api"));
    }

    /**
     * Moves the flat mesh bake into the viewer's URL layout: {@code c_p*.bin} into {@code cell/},
     * {@code projectile-N.png} to {@code projectile/N.png} and {@code tex/tN.png} to {@code tex/N.png}.
     */
    private static void arrangeStatic(Path api) throws IOException {
        Path cell = api.resolve("cell");
        Files.createDirectories(cell);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(api, "c_p*.bin")) {
            for (Path p : ds) {
                Files.move(p, cell.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path proj = api.resolve("projectile");
        Files.createDirectories(proj);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(api, "projectile-*.png")) {
            for (Path p : ds) {
                String fn = p.getFileName().toString();
                String n = fn.substring("projectile-".length());
                Files.move(p, proj.resolve(n), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path tex = api.resolve("tex");
        if (Files.isDirectory(tex)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(tex, "t*.png")) {
                for (Path p : ds) {
                    String fn = p.getFileName().toString();
                    if (fn.matches("t[0-9]+\\.png")) {
                        Files.move(p, tex.resolve(fn.substring(1)), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private Bake() {}
}
