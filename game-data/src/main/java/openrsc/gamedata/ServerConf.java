package openrsc.gamedata;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An OpenRSC server's {@code conf/server} tree, where every definition, locs file and landscape
 * this module loads is read from. A tree is accepted only if it has {@code defs/ItemDefs.json}.
 */
public record ServerConf(Path root) {

    /**
     * Finds the tree: {@code -Dopenrsc.serverConfDir}, else {@code $OPENRSC_SERVER_CONF}, else the
     * first {@code <ancestor>/openrsc/server/conf/server} above the working directory.
     *
     * @throws IllegalStateException when none is found, or a named one is not a tree
     */
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
        throw new IllegalStateException("Could not locate the OpenRSC server conf tree (looked for "
                + "<ancestor>/openrsc/server/conf/server above " + dir + "). "
                + "Pass -Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server "
                + "or set OPENRSC_SERVER_CONF.");
    }

    /**
     * The tree at {@code root}, checked as a found one is.
     *
     * @throws IllegalStateException when {@code root} is not a tree
     */
    public static ServerConf at(Path root) {
        return validated(root, "explicit path");
    }

    /**
     * Finds the client's {@code Client_Base/Cache} directory as {@link #resolve()} finds the tree:
     * {@code -Dopenrsc.clientCacheDir}, else {@code $OPENRSC_CLIENT_CACHE}, else the first
     * {@code <ancestor>/openrsc/Client_Base/Cache}. Accepted only if it has
     * {@code video/library.orsc}.
     *
     * @throws IllegalStateException when none is found, or a named one is not a cache
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
        throw new IllegalStateException("Could not locate the OpenRSC client cache (looked for "
                + "<ancestor>/openrsc/Client_Base/Cache above " + dir + "). "
                + "Pass -Dopenrsc.clientCacheDir=/path/to/Client_Base/Cache or set OPENRSC_CLIENT_CACHE.");
    }

    public Path defs() {
        return root.resolve("defs");
    }

    public Path locs() {
        return root.resolve("defs/locs");
    }

    public Path extras() {
        return root.resolve("defs/extras");
    }

    public Path data() {
        return root.resolve("data");
    }

    /** The server's Java source root, {@code src/com/openrsc/server}, in the same checkout. */
    public Path serverSrc() {
        return serverDir().resolve("src/com/openrsc/server");
    }

    /**
     * The server directory this tree is {@code conf/server} of, which holds the worlds'
     * {@code .conf} files.
     *
     * @throws IllegalStateException when the tree has no grandparent
     */
    public Path serverDir() {
        Path conf = root.getParent();
        Path server = conf == null ? null : conf.getParent();
        if (server == null) {
            throw new IllegalStateException("conf tree " + root + " is not <server>/conf/server");
        }
        return server;
    }

    private static ServerConf validated(Path p, String source) {
        if (!isConfRoot(p)) {
            throw new IllegalStateException("Server conf dir from " + source + " (" + p + ") does not look like "
                    + "openrsc/server/conf/server — defs/ItemDefs.json not found under it.");
        }
        return new ServerConf(p.toAbsolutePath().normalize());
    }

    private static boolean isConfRoot(Path p) {
        return Files.exists(p.resolve("defs/ItemDefs.json"));
    }

    private static Path validatedCache(Path p, String source) {
        if (!isClientCache(p)) {
            throw new IllegalStateException("Client cache from " + source + " (" + p + ") does not look like "
                    + "Client_Base/Cache — video/library.orsc not found under it.");
        }
        return p.toAbsolutePath().normalize();
    }

    private static boolean isClientCache(Path p) {
        return Files.exists(p.resolve("video/library.orsc"));
    }
}
