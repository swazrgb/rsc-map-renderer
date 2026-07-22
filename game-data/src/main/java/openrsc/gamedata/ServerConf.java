package openrsc.gamedata;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locator for the OpenRSC server's {@code conf/server} tree — the single authoritative source for
 * every game-data definition the bot consumes (item/npc/scenery defs, world locations, skill
 * extras, landscape + JAG maps). The bot reads the server checkout directly; there are no local
 * copies to drift out of sync.
 *
 * <p>Resolution order ({@link #resolve()}):
 * <ol>
 *   <li>{@code -Dopenrsc.serverConfDir} system property</li>
 *   <li>{@code OPENRSC_SERVER_CONF} environment variable</li>
 *   <li>walk-up search from the current working directory, trying
 *       {@code <ancestor>/openrsc/server/conf/server} at each level —
 *       covers running from the repo root, from {@code headless-bot-java},
 *       or from any module directory.</li>
 * </ol>
 * A candidate is accepted only if {@code defs/ItemDefs.json} exists under it.
 *
 * <p>The same checkout's client cache ({@code Client_Base/Cache}) is located in parallel by
 * {@link #clientCache()} — same walk-up over {@code <ancestor>/openrsc/…}, with the matching
 * {@code -Dopenrsc.clientCacheDir} / {@code OPENRSC_CLIENT_CACHE} overrides.
 */
public record ServerConf(Path root) {

  /**
   * {@code defs/} — ItemDefs.json, NpcDefs.json, DoorDef.xml, TileDef.xml, GameObjectDef.xml +
   * custom overlays.
   */
  public Path defs() {
    return root.resolve("defs");
  }

  /**
   * {@code defs/locs/} — SceneryLocs, BoundaryLocs, NpcLocs, GroundItems.
   */
  public Path locs() {
    return root.resolve("defs/locs");
  }

  /**
   * {@code defs/extras/} — skill-content XMLs (ObjectMining, ItemEdibleHeals, ObjectTelePoints,
   * ...).
   */
  public Path extras() {
    return root.resolve("defs/extras");
  }

  /**
   * {@code data/} — Authentic_Landscape.orsc + {@code maps/} JAG archives.
   */
  public Path data() {
    return root.resolve("data");
  }

  /**
   * The server's Java source root ({@code src/com/openrsc/server}) in the same checkout — used by
   * parity tests to compare our copied constants enums against the server originals.
   */
  public Path serverSrc() {
    return root.getParent().getParent().resolve("src/com/openrsc/server");
  }

  public static ServerConf resolve() {
    String prop = System.getProperty("openrsc.serverConfDir");
    if (prop != null && !prop.isBlank()) {
      return validated(Path.of(prop), "-Dopenrsc.serverConfDir");
    }
    String env = System.getenv("OPENRSC_SERVER_CONF");
    if (env != null && !env.isBlank()) {
      return validated(Path.of(env), "$OPENRSC_SERVER_CONF");
    }
    Path dir = Path.of("").toAbsolutePath();
    for (Path p = dir; p != null; p = p.getParent()) {
      Path candidate = p.resolve("openrsc/server/conf/server");
      if (isConfRoot(candidate)) {
        return new ServerConf(candidate.normalize());
      }
    }
    throw new IllegalStateException(
        "Could not locate the OpenRSC server conf tree (looked for "
        + "<ancestor>/openrsc/server/conf/server above " + dir + "). "
        + "Pass -Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server "
        + "or set OPENRSC_SERVER_CONF.");
  }

  public static ServerConf resolve(Path explicit) {
    return explicit != null ? validated(explicit, "explicit path") : resolve();
  }

  private static ServerConf validated(Path p, String source) {
    if (!isConfRoot(p)) {
      throw new IllegalStateException(
          "Server conf dir from " + source + " (" + p + ") does not look like "
          + "openrsc/server/conf/server — defs/ItemDefs.json not found under it.");
    }
    return new ServerConf(p.toAbsolutePath().normalize());
  }

  private static boolean isConfRoot(Path p) {
    return Files.exists(p.resolve("defs/ItemDefs.json"));
  }

  /**
   * Locate the OpenRSC client cache — {@code Client_Base/Cache}, whose {@code video/} subdir holds
   * the sprites/textures/models the 3D bake reads. Resolution mirrors {@link #resolve()}:
   * {@code -Dopenrsc.clientCacheDir} system property, then {@code OPENRSC_CLIENT_CACHE} environment
   * variable, then a walk-up for {@code <ancestor>/openrsc/Client_Base/Cache}. A candidate is
   * accepted only if {@code video/library.orsc} exists under it.
   */
  public static Path clientCache() {
    String prop = System.getProperty("openrsc.clientCacheDir");
    if (prop != null && !prop.isBlank()) {
      return validatedCache(Path.of(prop), "-Dopenrsc.clientCacheDir");
    }
    String env = System.getenv("OPENRSC_CLIENT_CACHE");
    if (env != null && !env.isBlank()) {
      return validatedCache(Path.of(env), "$OPENRSC_CLIENT_CACHE");
    }
    Path dir = Path.of("").toAbsolutePath();
    for (Path p = dir; p != null; p = p.getParent()) {
      Path candidate = p.resolve("openrsc/Client_Base/Cache");
      if (isClientCache(candidate)) {
        return candidate.normalize();
      }
    }
    throw new IllegalStateException(
        "Could not locate the OpenRSC client cache (looked for "
        + "<ancestor>/openrsc/Client_Base/Cache above " + dir + "). "
        + "Pass -Dopenrsc.clientCacheDir=/path/to/Client_Base/Cache or set OPENRSC_CLIENT_CACHE.");
  }

  private static Path validatedCache(Path p, String source) {
    if (!isClientCache(p)) {
      throw new IllegalStateException(
          "Client cache from " + source + " (" + p + ") does not look like "
          + "Client_Base/Cache — video/library.orsc not found under it.");
    }
    return p.toAbsolutePath().normalize();
  }

  private static boolean isClientCache(Path p) {
    return Files.exists(p.resolve("video/library.orsc"));
  }
}
