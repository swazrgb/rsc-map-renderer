package openrsc.nav;

import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.cache.NavCaches;
import org.junit.jupiter.api.Assumptions;

/**
 * The real server data and pathfinders over it, for this module's tests. Each is loaded once per JVM
 * and shared, since a whole-world load takes seconds and hundreds of MB. A test whose server conf tree
 * is not checked out is skipped, not failed.
 */
public final class RealDefs {

    /** A budget small enough for several cached engines in one test JVM, and ample for a test's asks. */
    public static final NavCacheBudget CACHE_BUDGET = new NavCacheBudget(64L << 20, 16L << 20, 128L << 20);

    private static final Object LOCK = new Object();
    private static ServerData env;
    private static boolean tried;
    private static final Object UNCACHED_LOCK = new Object();
    private static Pathfinder uncached;
    private static final Object CACHED_LOCK = new Object();
    private static Pathfinder cached;

    private RealDefs() {}

    /** The authentic world. */
    public static ServerData serverData() {
        ServerData loaded;
        synchronized (LOCK) {
            if (!tried) {
                tried = true;
                try {
                    ServerConf conf = ServerConf.resolve();
                    WorldProfile world = WorldProfile.authentic();
                    try (LandscapeSource landscape = world.openLandscape(conf)) {
                        env = ServerData.load(conf, world, landscape);
                    }
                } catch (Exception e) {
                    env = null;
                }
            }
            loaded = env;
        }
        Assumptions.assumeTrue(loaded != null, "server conf tree not available");
        return loaded;
    }

    public static final String COLESLAW = "rsccoleslaw";

    private static final java.util.Map<String, ServerData> WORLDS = new java.util.HashMap<>();

    /** The world of server conf {@code world}, such as {@link #COLESLAW}. */
    public static ServerData serverData(String world) {
        ServerData loaded;
        synchronized (WORLDS) {
            loaded = WORLDS.computeIfAbsent(world, RealDefs::loadOrNull);
        }
        Assumptions.assumeTrue(loaded != null, "server conf tree not available");
        return loaded;
    }

    private static ServerData loadOrNull(String world) {
        try {
            ServerConf conf = ServerConf.resolve();
            WorldProfile profile = WorldProfile.fromConf(conf, world);
            try (LandscapeSource landscape = profile.openLandscape(conf)) {
                return ServerData.load(conf, profile, landscape);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static CollisionMap collisionMap() {
        return serverData().collisionMap();
    }

    /**
     * A shared pathfinder over {@link #serverData()} that caches nothing between asks. A test that
     * asserts on an engine's own counters builds its own engine instead.
     */
    public static Pathfinder pathfinder() {
        ServerData data = serverData();
        synchronized (UNCACHED_LOCK) {
            if (uncached == null) {
                uncached = Pathfinder.load(data, NavCaches.none());
            }
            return uncached;
        }
    }

    private static final java.util.Map<String, Pathfinder> WORLD_PATHFINDERS = new java.util.HashMap<>();

    /** As {@link #pathfinder()}, over the world of server conf {@code world}. */
    public static Pathfinder pathfinder(String world) {
        ServerData data = serverData(world);
        synchronized (WORLD_PATHFINDERS) {
            return WORLD_PATHFINDERS.computeIfAbsent(world, w -> Pathfinder.load(data, NavCaches.none()));
        }
    }

    /** As {@link #pathfinder()}, with caches sized by {@link #CACHE_BUDGET}. */
    public static Pathfinder cachedPathfinder() {
        ServerData data = serverData();
        synchronized (CACHED_LOCK) {
            if (cached == null) {
                cached = Pathfinder.load(data, new CaffeineNavCaches(CACHE_BUDGET));
            }
            return cached;
        }
    }
}
