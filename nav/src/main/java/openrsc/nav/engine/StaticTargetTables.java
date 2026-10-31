package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.nav.Planner.Nearest;
import openrsc.nav.Planner.StaticTargets;
import openrsc.nav.TravelTime;

/**
 * The landscape's locs of one def id as nearest-target candidates: where each stands, the static
 * tiles it is engaged from, and the 8-tile chunk it stands in. A function of the map, built once per
 * (kind, id) on first ask and kept. Only a loc that stands on its place is a candidate
 * ({@link StaticLocIndex#sceneryStands}). Safe for concurrent use.
 */
final class StaticTargetTables {

    /**
     * One id's standing locs, index-aligned: {@code engage[i]} as {@code y * WIDTH + x}, the rows in each
     * chunk by {@link #chunkKey}, and {@code reach}, how far any loc's engage tile lies from the loc, so
     * a window widened by it holds every loc engaged from inside.
     */
    record Table(
            int id, int[] x, int[] y, int[] dir, int[][] engage, Long2ObjectOpenHashMap<int[]> byChunk, int reach) {
        int size() {
            return x.length;
        }

        int[] rowsIn(int cx, int cy) {
            return byChunk.get(chunkKey(cx, cy));
        }
    }

    private record Loc(Targetable.Kind kind, int id, int x, int z, int dir) implements Targetable {}

    static long chunkKey(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
    }

    private final CollisionMap map;
    private final ConcurrentHashMap<Long, Table> tables = new ConcurrentHashMap<>();

    StaticTargetTables(CollisionMap map) {
        this.map = map;
    }

    Table of(Targetable.Kind kind, int id) {
        return tables.computeIfAbsent(((long) kind.ordinal() << 32) | (id & 0xFFFFFFFFL), k -> build(kind, id));
    }

    private Table build(Targetable.Kind kind, int id) {
        StaticLocIndex index = map.staticLocIndex();
        boolean scenery = kind == Targetable.Kind.SCENERY;
        int[] handles = scenery ? index.sceneryWithId(id) : index.boundariesWithId(id);
        int n = 0;
        int[] xs = new int[handles.length], ys = new int[handles.length], dirs = new int[handles.length];
        int[][] engage = new int[handles.length][];
        Long2ObjectOpenHashMap<IntArrayList> chunks = new Long2ObjectOpenHashMap<>();
        int reach = 0;
        for (int h : handles) {
            if (!(scenery ? index.sceneryStands(h) : index.boundaryStands(h))) {
                continue;
            }
            int x = scenery ? index.sceneryX(h) : index.boundaryX(h);
            int y = scenery ? index.sceneryY(h) : index.boundaryY(h);
            int dir = scenery ? index.sceneryDir(h) : index.boundaryDir(h);
            List<Point> tiles = map.engageTiles(new Loc(kind, id, x, y, dir), Overlays.NONE);
            int[] packed = new int[tiles.size()];
            for (int i = 0; i < packed.length; i++) {
                Point t = tiles.get(i);
                reach = Math.max(reach, Point.chebyshev(t.x(), t.y(), x, y));
                packed[i] = t.y() * CollisionMap.WIDTH + t.x();
            }
            xs[n] = x;
            ys[n] = y;
            dirs[n] = dir;
            engage[n] = packed;
            chunks.computeIfAbsent(chunkKey(x >> 3, y >> 3), k -> new IntArrayList())
                    .add(n);
            n++;
        }
        Long2ObjectOpenHashMap<int[]> byChunk = new Long2ObjectOpenHashMap<>(chunks.size());
        chunks.long2ObjectEntrySet()
                .forEach(e -> byChunk.put(e.getLongKey(), e.getValue().toIntArray()));
        return new Table(
                id,
                Arrays.copyOf(xs, n),
                Arrays.copyOf(ys, n),
                Arrays.copyOf(dirs, n),
                Arrays.copyOf(engage, n),
                byChunk,
                reach);
    }

    /**
     * One query's candidates: its ids' tables, less the locs it excepts. A candidate is named by its
     * global index, its row plus the sizes of the tables before it, ids sorted: a function of the ids
     * alone, so labels kept under the ids and {@link Targets#excluded} name the same locs in every query.
     */
    Targets select(StaticTargets q) {
        int[] ids = Arrays.stream(q.ids()).distinct().sorted().toArray();
        Table[] ts = new Table[ids.length];
        int[] base = new int[ids.length];
        int total = 0;
        for (int i = 0; i < ids.length; i++) {
            ts[i] = of(q.kind(), ids[i]);
            base[i] = total;
            total += ts[i].size();
        }
        IntArrayList excluded = new IntArrayList();
        ChunkBox box = q.except();
        if (!box.isEmpty()) {
            for (int cy = box.originCy(); cy < box.originCy() + box.side(); cy++) {
                for (int cx = box.originCx(); cx < box.originCx() + box.side(); cx++) {
                    if (!box.isLiveChunk(cx, cy)) {
                        continue;
                    }
                    for (int t = 0; t < ts.length; t++) {
                        int[] rows = ts[t].rowsIn(cx, cy);
                        if (rows != null) {
                            for (int row : rows) {
                                excluded.add(base[t] + row);
                            }
                        }
                    }
                }
            }
        }
        for (long key : q.exceptKeys()) {
            int x = (int) (key >> 32);
            // A boundary's key carries its direction in the low byte.
            int y = q.kind() == Targetable.Kind.SCENERY ? (int) key : (int) ((key >>> 8) & 0xFFFFFF);
            for (int t = 0; t < ts.length; t++) {
                int[] rows = ts[t].rowsIn(x >> 3, y >> 3);
                if (rows == null) {
                    continue;
                }
                for (int row : rows) {
                    if (ts[t].x()[row] == x && ts[t].y()[row] == y && q.excepts(x, y, ts[t].dir()[row])) {
                        excluded.add(base[t] + row);
                    }
                }
            }
        }
        int[] ex = excluded.toIntArray();
        Arrays.sort(ex);
        int kept = 0;
        for (int i = 0; i < ex.length; i++) {
            if (i == 0 || ex[i] != ex[i - 1]) {
                ex[kept++] = ex[i];
            }
        }
        return new Targets(q.kind(), ids, ts, base, total, Arrays.copyOf(ex, kept));
    }

    record Targets(Targetable.Kind kind, int[] ids, Table[] tables, int[] base, int total, int[] excluded) {

        /** Whether every candidate is excepted. */
        boolean isEmpty() {
            return total == excluded.length;
        }

        boolean isExcluded(int global) {
            return excluded.length > 0 && Arrays.binarySearch(excluded, global) >= 0;
        }

        private int tableOf(int global) {
            int t = Arrays.binarySearch(base, global);
            if (t >= 0) {
                // An empty table shares its base with the next.
                while (t + 1 < base.length && base[t + 1] == global) {
                    t++;
                }
                return t;
            }
            return -t - 2;
        }

        /** Every candidate of the ids, excepted or not, by global index. */
        void forEachOfIds(IntConsumer candidate) {
            for (int t = 0; t < tables.length; t++) {
                for (int row = 0; row < tables[t].size(); row++) {
                    candidate.accept(base[t] + row);
                }
            }
        }

        /**
         * Every candidate not excepted that may have an engage tile within {@code radius} of {@code from}: a
         * superset, by chunk.
         */
        void forEachNear(Point from, int radius, IntConsumer candidate) {
            for (int t = 0; t < tables.length; t++) {
                int r = radius + tables[t].reach();
                for (int cy = (from.y() - r) >> 3; cy <= (from.y() + r) >> 3; cy++) {
                    for (int cx = (from.x() - r) >> 3; cx <= (from.x() + r) >> 3; cx++) {
                        int[] rows = tables[t].rowsIn(cx, cy);
                        if (rows == null) {
                            continue;
                        }
                        for (int row : rows) {
                            int global = base[t] + row;
                            if (!isExcluded(global)) {
                                candidate.accept(global);
                            }
                        }
                    }
                }
            }
        }

        /** Candidate {@code global}'s engage tiles, as {@code y * WIDTH + x}. */
        int[] engage(int global) {
            int t = tableOf(global);
            return tables[t].engage()[global - base[t]];
        }

        /** Arriving on any of {@code tiles}, as {@code y * WIDTH + x}. */
        static Dest dest(int[] tiles) {
            Area[] areas = new Area[tiles.length];
            for (int i = 0; i < tiles.length; i++) {
                areas[i] = Point.of(tiles[i] % CollisionMap.WIDTH, tiles[i] / CollisionMap.WIDTH);
            }
            return Dest.anyOf(areas);
        }

        Nearest nearest(int global, TravelTime time) {
            int t = tableOf(global);
            Table tb = tables[t];
            int i = global - base[t];
            return new Nearest(-1, tb.id(), tb.x()[i], tb.y()[i], tb.dir()[i], time);
        }
    }
}
