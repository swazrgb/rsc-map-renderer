package openrsc.nav.engine;

import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;

/**
 * Where aggressive NPCs make walking costly, for every bot at once: two values per tile, built once
 * from the static spawns of attackable, aggressive NPCs.
 *
 * <ul>
 *   <li>The base: {@link #PEAK} times the NPC's {@link #threatWeight} on its spawn tile, fading with
 *       distance to nothing just past its roam box and {@link #MARGIN}, and only where the NPC can
 *       reach from a tile it can walk to inside its box. The most of overlapping spawns, capped at
 *       {@link #TILE_CAP}.
 *   <li>The ceiling: the highest aggression threshold, {@code 2 * npcLevel + 1}, among the spawns
 *       that stamped the tile. A bot whose combat level is below it is charged the base; at or above
 *       it, nothing.
 * </ul>
 *
 * <p>The ceiling prices danger, not aggression: the server also lets an aggressive NPC attack any
 * level where both stand in the wilderness, and this map ignores that. The distinct thresholds make
 * the rung ladder ({@link #rungOf}).
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L381">NpcBehavior.aggressiveCheck</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L380">NpcBehavior.aggressiveCheck</a>
 */
final class DangerMap {

    /**
     * Bumped when a change to how this class's values are read can change a persisted plane; the values
     * themselves are hashed by content ({@link #digest}).
     */
    static final int PLANE_VERSION = 1;

    /** How many tiles past its roam box a spawn's danger reaches. */
    static final int MARGIN = 1;

    static final int PEAK = 24;
    static final int TILE_CAP = PEAK * 3;
    static final double THREAT_REF = 40.0;
    static final double THREAT_MIN = 0.25;
    static final double THREAT_MAX = 2.0;
    /**
     * A cluster's {@link DangerField#clusterPenalty} is its charged tiles' sum over this, at most
     * {@link #CLUSTER_CAP}.
     */
    static final int CLUSTER_DIVISOR = 4;

    static final int CLUSTER_CAP = 400;

    private final short[][] baseByCluster;
    private final short[][] ceilByCluster;
    private final int[] ladder;
    private final boolean any;

    /** Per cluster, the distinct ceilings of its charged tiles, ascending; null where it has none. */
    private final int[][] ceilingsByCluster;

    /** Danger-aware intra matrices by {@code cluster << 32 | view}: a cache of what the planes make, not data. */
    private final ConcurrentHashMap<Long, int[][]> viewMatrices = new ConcurrentHashMap<>();

    private DangerMap(short[][] base, short[][] ceil, int[] ladder, boolean any) {
        this.baseByCluster = base;
        this.ceilByCluster = ceil;
        this.ladder = ladder;
        this.any = any;
        this.ceilingsByCluster = new int[base.length][];
        for (int c = 0; c < base.length; c++) {
            if (base[c] == null) {
                continue;
            }
            TreeSet<Integer> distinct = new TreeSet<>();
            for (int i = 0; i < base[c].length; i++) {
                if (base[c][i] > 0) {
                    distinct.add((int) ceil[c][i]);
                }
            }
            ceilingsByCluster[c] = distinct.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /**
     * How many of cluster {@code c}'s ceilings a bot at combat level {@code gateCl} has reached. Two
     * levels with the same view are charged exactly the same tiles of {@code c}, so what is built per
     * cluster keys on the view rather than the rung.
     */
    int viewOf(int c, int gateCl) {
        int[] ceilings = ceilingsByCluster[c];
        if (ceilings == null) {
            return 0;
        }
        int lo = 0;
        int hi = ceilings.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ceilings[mid] <= gateCl) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** {@code field}'s danger-aware matrices for {@code c}, {@code {weights, times}}, built once per view. */
    int[][] viewMatrices(ClusterGraph graph, DangerField field, int c) {
        long key = (long) c << 32 | (field.viewSignature(c) & 0xFFFFFFFFL);
        return viewMatrices.computeIfAbsent(key, k -> {
            int n = graph.clusterNodeCount(c);
            int[] times = new int[n * n];
            int[] weights = graph.computeDangerAwareMatrix(c, field, times);
            return new int[][] {weights, times};
        });
    }

    /** The map of {@code spawns}, which may be null for none. */
    static DangerMap build(List<ServerData.NpcSpawn> spawns, IntFunction<NpcDef> defs, CollisionMap map) {
        int total = ClusterGeometry.TOTAL_CLUSTERS;
        short[][] base = new short[total][];
        short[][] ceil = new short[total][];
        TreeSet<Integer> ceilings = new TreeSet<>();
        boolean any = false;
        if (spawns != null) {
            for (ServerData.NpcSpawn s : spawns) {
                NpcDef def = defs.apply(s.id());
                if (def == null || !def.attackable() || !def.aggressive()) {
                    continue;
                }
                int threshold = def.combatLevel() * 2 + 1;
                if (stamp(base, ceil, s, def.combatLevel(), threshold, map)) {
                    ceilings.add(threshold);
                    any = true;
                }
            }
        }
        int[] ladder = ceilings.stream().mapToInt(Integer::intValue).toArray();
        return new DangerMap(base, ceil, ladder, any);
    }

    boolean any() {
        return any;
    }

    /** Feeds the ladder and both planes into a store stamp. */
    void digest(java.security.MessageDigest md) {
        Digests.ints(md, ladder);
        for (int c = 0; c < baseByCluster.length; c++) {
            Digests.shorts(md, baseByCluster[c]);
            Digests.shorts(md, ceilByCluster[c]);
        }
    }

    /** How many thresholds lie at or below {@code combatLevel}: every level on one rung sees the same tiles charged. */
    int rungOf(int combatLevel) {
        int lo = 0, hi = ladder.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ladder[mid] <= combatLevel) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** A combat level on {@code rung}. */
    int representativeCl(int rung) {
        return rung == 0 ? 0 : ladder[rung - 1];
    }

    /** Cluster {@code c}'s base at local row-major index {@code idx}, before any gate; 0 where it has none. */
    int rawBaseAt(int c, int idx) {
        short[] a = baseByCluster[c];
        return a == null ? 0 : a[idx];
    }

    /** As {@link #rawBaseAt}, the ceiling. */
    int ceilingAt(int c, int idx) {
        short[] a = ceilByCluster[c];
        return a == null ? 0 : a[idx];
    }

    boolean clusterHasDanger(int c) {
        return baseByCluster[c] != null;
    }

    /** Cluster {@code c}'s base by local index, or null where it has no danger. */
    short[] baseCells(int c) {
        return baseByCluster[c];
    }

    /** Cluster {@code c}'s ceilings by local index, or null where it has no danger. */
    short[] ceilCells(int c) {
        return ceilByCluster[c];
    }

    int clusterCount() {
        return baseByCluster.length;
    }

    private static boolean stamp(
            short[][] base, short[][] ceil, ServerData.NpcSpawn s, int npcCl, int threshold, CollisionMap map) {
        int cs = ClusterGeometry.CLUSTER_SIZE;
        double w = threatWeight(npcCl);
        int spawnX = s.startX(), spawnZ = s.startZ();
        int roamReach = Math.max(
                Math.max(s.maxX() - spawnX, spawnX - s.minX()), Math.max(s.maxZ() - spawnZ, spawnZ - s.minZ()));
        int reach = roamReach + MARGIN;
        boolean[] standable = reachableInBox(map, s, spawnX, spawnZ);
        boolean any = false;
        for (int x = s.minX() - MARGIN; x <= s.maxX() + MARGIN; x++) {
            for (int z = s.minZ() - MARGIN; z <= s.maxZ() + MARGIN; z++) {
                int dSpawn = Point.chebyshev(x, z, spawnX, spawnZ);
                int add = dSpawn > reach ? 0 : (int) Math.round(PEAK * w * (reach + 1 - dSpawn) / (double) (reach + 1));
                if (add <= 0) {
                    continue;
                }
                if (standable != null && !threatenedFromStandable(standable, s, x, z, map)) {
                    continue;
                }
                int c = ClusterGeometry.clusterOf(x, z);
                if (c < 0 || c >= base.length) {
                    continue;
                }
                int lx = x - ClusterGeometry.minX(c);
                int ly = z - ClusterGeometry.minY(c);
                if (lx < 0 || ly < 0 || lx >= cs || ly >= cs) {
                    continue;
                }
                if (base[c] == null) {
                    base[c] = new short[cs * cs];
                    ceil[c] = new short[cs * cs];
                }
                int idx = ly * cs + lx;
                base[c][idx] = (short) Math.min(TILE_CAP, Math.max(base[c][idx], add));
                ceil[c][idx] = (short) Math.max(ceil[c][idx], Math.min(Short.MAX_VALUE, threshold));
                any = true;
            }
        }
        return any;
    }

    private static boolean[] reachableInBox(CollisionMap map, ServerData.NpcSpawn s, int spawnX, int spawnZ) {
        if (map == null) {
            return null;
        }
        int minX = s.minX(), minZ = s.minZ(), maxX = s.maxX(), maxZ = s.maxZ();
        if (spawnX < minX || spawnX > maxX || spawnZ < minZ || spawnZ > maxZ || map.componentId(spawnX, spawnZ) == 0) {
            return null;
        }
        int boxW = maxX - minX + 1, boxH = maxZ - minZ + 1;
        boolean[] seen = new boolean[boxW * boxH];
        int[] queue = new int[boxW * boxH];
        int head = 0, tail = 0;
        int seed = (spawnZ - minZ) * boxW + (spawnX - minX);
        seen[seed] = true;
        queue[tail++] = seed;
        while (head < tail) {
            int cur = queue[head++];
            int cx = minX + (cur % boxW);
            int cz = minZ + (cur / boxW);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = cx + dx, nz = cz + dz;
                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                        continue;
                    }
                    int nIdx = (nz - minZ) * boxW + (nx - minX);
                    if (seen[nIdx] || !map.canStep(cx, cz, nx, nz, Overlays.NONE)) {
                        continue;
                    }
                    seen[nIdx] = true;
                    queue[tail++] = nIdx;
                }
            }
        }
        return seen;
    }

    private static boolean threatenedFromStandable(
            boolean[] standable, ServerData.NpcSpawn s, int x, int z, CollisionMap map) {
        int minX = s.minX(), minZ = s.minZ(), maxX = s.maxX(), maxZ = s.maxZ();
        int boxW = maxX - minX + 1;
        for (int dz = -MARGIN; dz <= MARGIN; dz++) {
            int rz = z + dz;
            if (rz < minZ || rz > maxZ) {
                continue;
            }
            for (int dx = -MARGIN; dx <= MARGIN; dx++) {
                int rx = x + dx;
                if (rx < minX || rx > maxX) {
                    continue;
                }
                if (standable[(rz - minZ) * boxW + (rx - minX)] && map.canReachMob(rx, rz, x, z, Overlays.NONE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * An NPC's weight against the others: its combat level over {@link #THREAT_REF}, clamped to
     * {@link #THREAT_MIN} and {@link #THREAT_MAX}. The bot's level enters only through the ceiling.
     */
    static double threatWeight(int npcCombatLevel) {
        return Math.max(THREAT_MIN, Math.min(THREAT_MAX, npcCombatLevel / THREAT_REF));
    }
}
