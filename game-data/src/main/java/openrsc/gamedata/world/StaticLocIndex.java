package openrsc.gamedata.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Rect;

/**
 * Every static scenery and boundary loc, indexed by 8x8 chunk, built from the same {@link LocStamps}
 * pass that stamps a {@link CollisionMap}'s planes. A loc is named by its handle, its position in
 * load order within its kind. Locs that add no collision are indexed too; only stamped ones carry
 * contributions.
 *
 * <p>When several scenery locs share an origin tile, or several boundaries a tile and direction, the
 * last in load order is the one that stands: the server removes the object already there when it
 * registers another.
 *
 * <p>Immutable apart from the by-id lookups, built on first use; safe to share across threads.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L491">World.registerGameObject</a>
 */
public final class StaticLocIndex {

    static final int CHUNKS_X = CollisionMap.WIDTH >> 3;
    static final int CHUNKS_Y = CollisionMap.HEIGHT >> 3;
    static final int CHUNK_COUNT = CHUNKS_X * CHUNKS_Y;

    private final int[] scX, scY, scId, scDir;
    private final byte[] scW, scH;
    private final int maxFootprint;

    private final boolean[] scStamped;
    private final int[][] scContrib;
    private final int[] bdX, bdY, bdId, bdDir;
    private final boolean[] bdStamped;
    private final int[][] bdContrib;

    private final int[][] scOriginByChunk;
    private final int[][] bdOriginByChunk;

    private StaticLocIndex(Builder b) {
        this.scX = b.scX.toArray();
        this.scY = b.scY.toArray();
        this.scId = b.scId.toArray();
        this.scDir = b.scDir.toArray();
        this.scW = b.scW.toBytes();
        this.scH = b.scH.toBytes();
        int longest = 1;
        for (int i = 0; i < scW.length; i++) {
            longest = Math.max(longest, Math.max(scW[i], scH[i]));
        }
        this.maxFootprint = longest;
        this.scStamped = b.scStamped();
        this.scContrib = b.scContrib.toArray(new int[0][]);
        this.bdX = b.bdX.toArray();
        this.bdY = b.bdY.toArray();
        this.bdId = b.bdId.toArray();
        this.bdDir = b.bdDir.toArray();
        this.bdStamped = b.bdStamped();
        this.bdContrib = b.bdContrib.toArray(new int[0][]);
        this.scOriginByChunk = freeze(b.scOriginByChunk);
        this.bdOriginByChunk = freeze(b.bdOriginByChunk);
    }

    /**
     * Indexes {@code scenery} and {@code boundaries}. A {@code null} list, or a {@code null} def table
     * for it, leaves that kind empty; a loc whose origin is off the map is left out.
     */
    public static StaticLocIndex build(
            List<SceneryLocs.Loc> scenery,
            ObjectDefs objectDefs,
            List<BoundaryLocs.Loc> boundaries,
            DoorDefs doorDefs) {
        Builder b = new Builder();
        if (scenery != null && objectDefs != null) {
            for (SceneryLocs.Loc loc : scenery) {
                int ox = loc.pos().x(), oy = loc.pos().y();
                if (!inBounds(ox, oy)) {
                    continue;
                }
                IntList contrib = new IntList();
                LocStamps.Result r =
                        LocStamps.enumerateScenery(loc, objectDefs, (x, y, mask) -> contrib.add(pack(x, y, mask)));
                int handle = b.scX.size();
                b.scX.add(ox);
                b.scY.add(oy);
                b.scId.add(loc.id());
                b.scDir.add(loc.direction());
                ObjectDef def = objectDefs.get(loc.id());
                Rect fp = Rect.footprint(
                        ox,
                        oy,
                        def == null ? 1 : Math.max(1, def.width()),
                        def == null ? 1 : Math.max(1, def.height()),
                        loc.direction());
                b.scW.add(fp.maxX() - fp.minX() + 1);
                b.scH.add(fp.maxY() - fp.minY() + 1);
                b.scStampedList.add(r == LocStamps.Result.STAMPED);
                b.scContrib.add(r == LocStamps.Result.STAMPED ? contrib.toArray() : null);
                addTo(b.scOriginByChunk, chunkOf(ox, oy), handle);
            }
        }
        if (boundaries != null && doorDefs != null) {
            for (BoundaryLocs.Loc loc : boundaries) {
                int ox = loc.pos().x(), oy = loc.pos().y();
                if (!inBounds(ox, oy)) {
                    continue;
                }
                IntList contrib = new IntList();
                LocStamps.Result r =
                        LocStamps.enumerateBoundary(loc, doorDefs, (x, y, mask) -> contrib.add(pack(x, y, mask)));
                int handle = b.bdX.size();
                b.bdX.add(ox);
                b.bdY.add(oy);
                b.bdId.add(loc.id());
                b.bdDir.add(loc.direction());
                b.bdStampedList.add(r == LocStamps.Result.STAMPED);
                b.bdContrib.add(r == LocStamps.Result.STAMPED ? contrib.toArray() : null);
                addTo(b.bdOriginByChunk, chunkOf(ox, oy), handle);
            }
        }
        return new StaticLocIndex(b);
    }

    /** How many scenery locs are indexed, stamped or not. */
    public int sceneryCount() {
        return scX.length;
    }

    public int stampedSceneryCount() {
        return countTrue(scStamped);
    }

    public int sceneryX(int handle) {
        return scX[handle];
    }

    public int sceneryY(int handle) {
        return scY[handle];
    }

    public int sceneryId(int handle) {
        return scId[handle];
    }

    public int sceneryDir(int handle) {
        return scDir[handle];
    }

    public boolean sceneryStamped(int handle) {
        return scStamped[handle];
    }

    /** Every scenery loc whose origin tile is in chunk {@code (cx, cy)}. */
    public void forEachSceneryOriginInChunk(int cx, int cy, IntConsumer handleConsumer) {
        forEach(scOriginByChunk, cx, cy, handleConsumer);
    }

    public boolean hasSceneryInChunk(int cx, int cy) {
        return any(scOriginByChunk, cx, cy);
    }

    public boolean hasBoundaryInChunk(int cx, int cy) {
        return any(bdOriginByChunk, cx, cy);
    }

    /** Every scenery loc whose origin is {@code (x, y)}, the replaced ones included. */
    public void forEachSceneryAt(int x, int y, IntConsumer handleConsumer) {
        int[] list = inBounds(x, y) ? scOriginByChunk[chunkOf(x, y)] : null;
        if (list == null) {
            return;
        }
        for (int handle : list) {
            if (scX[handle] == x && scY[handle] == y) {
                handleConsumer.accept(handle);
            }
        }
    }

    public boolean hasSceneryAt(int x, int y) {
        int[] list = inBounds(x, y) ? scOriginByChunk[chunkOf(x, y)] : null;
        if (list == null) {
            return false;
        }
        for (int handle : list) {
            if (scX[handle] == x && scY[handle] == y) {
                return true;
            }
        }
        return false;
    }

    /** The scenery loc that stands with its origin on {@code (x, y)}, or -1. */
    public int sceneryAt(int x, int y) {
        int[] list = inBounds(x, y) ? scOriginByChunk[chunkOf(x, y)] : null;
        if (list == null) {
            return -1;
        }
        int found = -1;
        for (int handle : list) {
            if (scX[handle] == x && scY[handle] == y) {
                found = handle;
            }
        }
        return found;
    }

    /** Whether no later loc on the same origin replaced this one. */
    public boolean sceneryStands(int handle) {
        return sceneryAt(scX[handle], scY[handle]) == handle;
    }

    /**
     * The standing scenery loc whose footprint covers {@code (x, y)}, or -1: the one with its origin
     * there if any, else the last in load order.
     */
    public int sceneryCovering(int x, int y) {
        int anchored = sceneryAt(x, y);
        if (anchored >= 0) {
            return anchored;
        }
        int found = -1;
        for (int cy = (y - maxFootprint + 1) >> 3; cy <= y >> 3; cy++) {
            for (int cx = (x - maxFootprint + 1) >> 3; cx <= x >> 3; cx++) {
                if (cx < 0 || cx >= CHUNKS_X || cy < 0 || cy >= CHUNKS_Y) {
                    continue;
                }
                int[] list = scOriginByChunk[cy * CHUNKS_X + cx];
                if (list == null) {
                    continue;
                }
                for (int h : list) {
                    int ox = scX[h], oy = scY[h];
                    if (x >= ox && y >= oy && x < ox + scW[h] && y < oy + scH[h] && sceneryStands(h)) {
                        found = Math.max(found, h);
                    }
                }
            }
        }
        return found;
    }

    /** Every scenery loc of def {@code id}, in load order, the replaced ones included. */
    public int[] sceneryWithId(int id) {
        return byId(true).getOrDefault(id, NO_HANDLES);
    }

    /** Every boundary loc of def {@code id}, in load order, the replaced ones included. */
    public int[] boundariesWithId(int id) {
        return byId(false).getOrDefault(id, NO_HANDLES);
    }

    private static final int[] NO_HANDLES = new int[0];

    private volatile Map<Integer, int[]> sceneryById;
    private volatile Map<Integer, int[]> boundaryById;

    private Map<Integer, int[]> byId(boolean scenery) {
        Map<Integer, int[]> held = scenery ? sceneryById : boundaryById;
        if (held != null) {
            return held;
        }
        int[] ids = scenery ? scId : bdId;
        Map<Integer, IntList> lists = new HashMap<>();
        for (int h = 0; h < ids.length; h++) {
            lists.computeIfAbsent(ids[h], k -> new IntList()).add(h);
        }
        Map<Integer, int[]> built = new HashMap<>(lists.size() * 2);
        lists.forEach((id, list) -> built.put(id, list.toArray()));
        if (scenery) {
            sceneryById = built;
        } else {
            boundaryById = built;
        }
        return built;
    }

    /** How many boundary locs are indexed, stamped or not. */
    public int boundaryCount() {
        return bdX.length;
    }

    public int stampedBoundaryCount() {
        return countTrue(bdStamped);
    }

    public int boundaryX(int handle) {
        return bdX[handle];
    }

    public int boundaryY(int handle) {
        return bdY[handle];
    }

    public int boundaryId(int handle) {
        return bdId[handle];
    }

    public int boundaryDir(int handle) {
        return bdDir[handle];
    }

    public void forEachBoundaryInChunk(int cx, int cy, IntConsumer handleConsumer) {
        forEach(bdOriginByChunk, cx, cy, handleConsumer);
    }

    /** Every boundary loc on edge {@code (x, y, dir)}, the replaced ones included. */
    public void forEachBoundaryAt(int x, int y, int dir, IntConsumer handleConsumer) {
        int[] list = inBounds(x, y) ? bdOriginByChunk[chunkOf(x, y)] : null;
        if (list == null) {
            return;
        }
        for (int handle : list) {
            if (bdX[handle] == x && bdY[handle] == y && bdDir[handle] == dir) {
                handleConsumer.accept(handle);
            }
        }
    }

    /** The boundary loc that stands on edge {@code (x, y, dir)}, or -1. */
    public int boundaryAt(int x, int y, int dir) {
        int[] list = inBounds(x, y) ? bdOriginByChunk[chunkOf(x, y)] : null;
        if (list == null) {
            return -1;
        }
        int found = -1;
        for (int handle : list) {
            if (bdX[handle] == x && bdY[handle] == y && bdDir[handle] == dir) {
                found = handle;
            }
        }
        return found;
    }

    /** Whether no later boundary on the same tile and direction replaced this one. */
    public boolean boundaryStands(int handle) {
        return boundaryAt(bdX[handle], bdY[handle], bdDir[handle]) == handle;
    }

    /** Every contribution of every stamped loc, scenery first. */
    public void forEachContribution(LocStamps.TileMask out) {
        for (int[] c : scContrib) {
            emit(c, out);
        }
        for (int[] c : bdContrib) {
            emit(c, out);
        }
    }

    /**
     * The tile x of a packed contribution: {@code (y * CollisionMap.WIDTH + x) << 8 | mask}.
     */
    public static int contribX(int packed) {
        return (packed >>> 8) % CollisionMap.WIDTH;
    }

    public static int contribY(int packed) {
        return (packed >>> 8) / CollisionMap.WIDTH;
    }

    public static int contribMask(int packed) {
        return packed & 0xFF;
    }

    static int pack(int x, int y, int mask) {
        return ((y * CollisionMap.WIDTH + x) << 8) | (mask & 0xFF);
    }

    static int chunkOf(int x, int y) {
        return (y >> 3) * CHUNKS_X + (x >> 3);
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && x < CollisionMap.WIDTH && y >= 0 && y < CollisionMap.HEIGHT;
    }

    private static boolean any(int[][] byChunk, int cx, int cy) {
        if (cx < 0 || cx >= CHUNKS_X || cy < 0 || cy >= CHUNKS_Y) {
            return false;
        }
        int[] list = byChunk[cy * CHUNKS_X + cx];
        return list != null && list.length > 0;
    }

    private static void forEach(int[][] byChunk, int cx, int cy, IntConsumer consumer) {
        if (cx < 0 || cx >= CHUNKS_X || cy < 0 || cy >= CHUNKS_Y) {
            return;
        }
        int[] list = byChunk[cy * CHUNKS_X + cx];
        if (list == null) {
            return;
        }
        for (int handle : list) {
            consumer.accept(handle);
        }
    }

    private static void addTo(Map<Integer, IntList> map, int chunk, int handle) {
        map.computeIfAbsent(chunk, k -> new IntList()).add(handle);
    }

    private static int[][] freeze(Map<Integer, IntList> map) {
        int[][] out = new int[CHUNK_COUNT][];
        for (Map.Entry<Integer, IntList> e : map.entrySet()) {
            out[e.getKey()] = e.getValue().toArray();
        }
        return out;
    }

    private static int countTrue(boolean[] a) {
        int n = 0;
        for (boolean v : a) {
            if (v) {
                n++;
            }
        }
        return n;
    }

    private static void emit(int[] contrib, LocStamps.TileMask out) {
        if (contrib == null) {
            return;
        }
        for (int p : contrib) {
            out.accept(contribX(p), contribY(p), contribMask(p));
        }
    }

    private static final class Builder {
        final IntList scX = new IntList(), scY = new IntList(), scId = new IntList(), scDir = new IntList();
        final IntList scW = new IntList(), scH = new IntList();
        final ArrayList<Boolean> scStampedList = new ArrayList<>();
        final ArrayList<int[]> scContrib = new ArrayList<>();
        final IntList bdX = new IntList(), bdY = new IntList(), bdId = new IntList(), bdDir = new IntList();
        final ArrayList<Boolean> bdStampedList = new ArrayList<>();
        final ArrayList<int[]> bdContrib = new ArrayList<>();
        final Map<Integer, IntList> scOriginByChunk = new HashMap<>();
        final Map<Integer, IntList> bdOriginByChunk = new HashMap<>();

        boolean[] scStamped() {
            return toBooleans(scStampedList);
        }

        boolean[] bdStamped() {
            return toBooleans(bdStampedList);
        }

        private static boolean[] toBooleans(List<Boolean> list) {
            boolean[] out = new boolean[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = list.get(i);
            }
            return out;
        }
    }

    private static final class IntList {
        int[] a = new int[8];
        int n;

        void add(int v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = v;
        }

        byte[] toBytes() {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                out[i] = (byte) a[i];
            }
            return out;
        }

        int size() {
            return n;
        }

        int[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
