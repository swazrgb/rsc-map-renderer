package openrsc.maprender.bake;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import openrsc.gamedata.ServerConf;

/**
 * CLI: bake the complete static {@code /api/world3d/*} asset tree the WebGL
 * viewer consumes, so a dumb static HTTP server (e.g. GitHub Pages) can host
 * the viewer with no runtime backend.
 *
 * <pre>
 *   java -jar world3d-bake.jar [clientCacheDir] &lt;outDir&gt;
 * </pre>
 *
 * <ul>
 *   <li>{@code clientCacheDir} — the stock OpenRSC client cache ({@code Client_Base/Cache}, whose
 *       {@code video/} holds Authentic_Landscape.orsc, Authentic_Sprites.orsc, library.orsc, …).
 *       Optional: omit it and it auto-resolves via {@link ServerConf#clientCache()} (walk-up for
 *       {@code <ancestor>/openrsc/Client_Base/Cache}, overridable with
 *       {@code -Dopenrsc.clientCacheDir} / {@code OPENRSC_CLIENT_CACHE}).</li>
 *   <li>{@code outDir} — the site root. The servable tree is written under
 *       {@code <outDir>/api/world3d/} so a host serving {@code <outDir>} answers
 *       the viewer's absolute {@code /api/world3d/*} fetches directly.</li>
 * </ul>
 *
 * <p>Player appearances are <b>not</b> pre-baked: the viewer composites any
 * {@code layers|colours} token in the browser from the per-layer atlas baked
 * below (see {@link PlayerLayerAtlasBaker} + the viewer's {@code playerCompositor}).
 *
 * <p>The server game-data tree (locs + JAG maps) is located by {@link ServerConf}:
 * pass {@code -Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server}, set
 * {@code OPENRSC_SERVER_CONF}, or run from inside the {@code openrsc} checkout.
 */
public final class Bake {

  public static void main(String[] args) throws Exception {
    // [clientCacheDir] outDir — the cache auto-resolves (ServerConf.clientCache) when omitted, so a
    // one-arg invocation from inside the openrsc checkout needs no cache path.
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
    Path api = siteRoot.resolve("api").resolve("world3d");
    Files.createDirectories(api);

    // 1. Core world tree (mesh cells, textures, objlib, doorlib, boundaries,
    //    npc/item/font atlases, splats, projectiles, and the per-layer player-
    //    sprite atlas the viewer composites appearances from) — flat layout,
    //    exactly as the live WorldMeshController expects it.
    WorldMeshExporter.export(cacheDir, api.toFile(), System.out::println);

    // 2. Auxiliary JSON the viewer fetches outside /api/world3d/ (scenery
    //    placements + atlas, npc spawns, wearables).
    AuxDataBaker.export(cacheDir, siteRoot, System.out::println);

    // 3. Rework the flat bake into the exact URL layout the viewer fetches
    //    (the live controller does this translation via routing; a static host
    //    needs the files physically placed to match the request paths).
    arrangeStatic(api);
    System.out.println("static site tree ready: " + siteRoot.resolve("api"));
  }

  /**
   * Three URL/path mismatches between the flat bake and the viewer's fetch URLs:
   * <ul>
   *   <li>{@code /cell/c_p...bin} — flat {@code c_p...bin} → {@code cell/}</li>
   *   <li>{@code /projectile/N.png} — flat {@code projectile-N.png} → {@code projectile/N.png}</li>
   *   <li>{@code /tex/ID.png} — dumped {@code tex/tID.png} → {@code tex/ID.png}</li>
   * </ul>
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
        String fn = p.getFileName().toString();           // projectile-3.png
        String n = fn.substring("projectile-".length());  // 3.png
        Files.move(p, proj.resolve(n), StandardCopyOption.REPLACE_EXISTING);
      }
    }

    Path tex = api.resolve("tex");
    if (Files.isDirectory(tex)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(tex, "t*.png")) {
        for (Path p : ds) {
          String fn = p.getFileName().toString();          // t42.png
          if (fn.matches("t[0-9]+\\.png")) {
            Files.move(p, tex.resolve(fn.substring(1)), StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
  }

  private Bake() {}
}
