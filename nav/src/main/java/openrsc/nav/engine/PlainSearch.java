package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.Nullable;

/** A {@link Search} over a {@link PlainRouting}: each half a Dijkstra from its seeds over the whole graph. */
final class PlainSearch implements Search {

    private static final long INF = PackedCost.INF;

    private final PlainRouting routing;
    private final Half forward;
    private final Half backward;

    PlainSearch(PlainRouting routing) {
        this.routing = routing;
        this.forward = new Half(routing, true);
        this.backward = new Half(routing, false);
    }

    @Override
    public Search.Half forward() {
        return forward;
    }

    @Override
    public Search.Half backward() {
        return backward;
    }

    @Override
    public @Nullable List<int[]> chainToLabels(Metric m, int[] ranks, long[] costs, int[] preds) {
        long best = INF;
        int at = -1;
        for (int i = 0; i < ranks.length; i++) {
            int r = ranks[i];
            if (forward.reached(r) && forward.dist[r] != INF && forward.dist[r] + costs[i] < best) {
                best = forward.dist[r] + costs[i];
                at = r;
            }
        }
        return at < 0 ? null : chain(at, forwardEdges(at), labelledEdges(at, ranks, preds));
    }

    @Override
    public @Nullable List<int[]> chainToBank(Metric m, int bank) {
        int node = routing.graph.bankNode(bank);
        if (!forward.reached(node) || forward.dist[node] == INF) {
            return null;
        }
        return chain(node, forwardEdges(node), new IntArrayList());
    }

    @Override
    public @Nullable List<int[]> chainFromBank(Metric m, int bank, int[] ranks, long[] costs, int[] preds) {
        int node = routing.graph.bankNode(bank);
        int i = Arrays.binarySearch(ranks, node);
        if (i < 0 || costs[i] == INF) {
            return null;
        }
        return chain(node, new IntArrayList(), labelledEdges(node, ranks, preds));
    }

    /** {@code r}'s forward edges, from it back toward the seeds. */
    private IntArrayList forwardEdges(int r) {
        IntArrayList edges = new IntArrayList();
        for (; forward.pred[r] >= 0; r = routing.tail(forward.pred[r])) {
            edges.add(forward.pred[r]);
        }
        return edges;
    }

    private IntArrayList labelledEdges(int r, int[] ranks, int[] preds) {
        IntArrayList edges = new IntArrayList();
        for (int e = preds[Arrays.binarySearch(ranks, r)];
                e >= 0;
                e = preds[Arrays.binarySearch(ranks, routing.tail(e))]) {
            edges.add(e);
        }
        return edges;
    }

    private List<int[]> chain(int meet, IntArrayList upEdges, IntArrayList downEdges) {
        IntArrayList to = new IntArrayList();
        IntArrayList tags = new IntArrayList();
        int start = upEdges.isEmpty() ? meet : routing.tail(upEdges.getInt(upEdges.size() - 1));
        for (int i = upEdges.size() - 1; i >= 0; i--) {
            int e = upEdges.getInt(i);
            to.add(routing.head(e));
            tags.add(routing.tag[routing.arcOf(e)]);
        }
        for (int i = 0; i < downEdges.size(); i++) {
            int e = downEdges.getInt(i);
            to.add(routing.tail(e));
            tags.add(routing.tag[routing.arcOf(e)]);
        }
        List<int[]> chain = new ArrayList<>(to.size() + 1);
        int k = 0;
        if (routing.isSource(start)) {
            chain.add(new int[] {to.getInt(0), tags.getInt(0)});
            k = 1;
        } else {
            chain.add(new int[] {start, Engine.TAG_SEED});
        }
        for (; k < to.size(); k++) {
            if (!routing.isSource(to.getInt(k))) {
                chain.add(new int[] {to.getInt(k), tags.getInt(k)});
            }
        }
        return chain;
    }

    /** One direction's Dijkstra: out-arcs from the seeds for a forward half, in-arcs back from them otherwise. */
    static final class Half implements Search.Half {
        private final PlainRouting routing;
        private final boolean forward;
        final long[] dist;
        final int[] pred;
        private final int[] mark;
        private int stamp = 1;
        private int[] reached = new int[256];
        private int size;
        private long[] heapKey = new long[256];
        private int[] heapNode = new int[256];
        private int heapSize;
        private WeakReference<Metric> heldMetric;
        private Object heldSource;

        Half(PlainRouting routing, boolean forward) {
            this.routing = routing;
            this.forward = forward;
            this.dist = new long[routing.nodeCount];
            this.pred = new int[routing.nodeCount];
            this.mark = new int[routing.nodeCount];
        }

        @Override
        public void reset() {
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(mark, 0);
                stamp = 1;
            }
            size = 0;
            heldMetric = null;
            heldSource = null;
        }

        @Override
        public boolean seed(int node, long cost) {
            heldMetric = null;
            heldSource = null;
            if (mark[node] == stamp) {
                if (cost < dist[node]) {
                    dist[node] = cost;
                    pred[node] = -1;
                    return true;
                }
                return false;
            }
            label(node, cost, -1);
            return true;
        }

        private void label(int node, long cost, int edge) {
            mark[node] = stamp;
            dist[node] = cost;
            pred[node] = edge;
            if (size == reached.length) {
                reached = Arrays.copyOf(reached, size * 2);
            }
            reached[size++] = node;
        }

        @Override
        public void run(Metric m) {
            long[] w = ((PlainMetric) m).weights;
            int[] first = forward ? routing.outFirst : routing.inFirst;
            int[] arcs = forward ? routing.outArc : routing.inArc;
            int[] far = forward ? routing.to : routing.from;
            int offset = forward ? 0 : routing.arcCount;
            heapSize = 0;
            for (int i = 0; i < size; i++) {
                push(dist[reached[i]], reached[i]);
            }
            while (heapSize > 0) {
                long d = heapKey[0];
                int u = heapNode[0];
                pop();
                if (d > dist[u]) {
                    continue;
                }
                for (int k = first[u]; k < first[u + 1]; k++) {
                    int a = arcs[k];
                    long wa = w[a];
                    if (wa == INF) {
                        continue;
                    }
                    int v = far[a];
                    long c = d + wa;
                    if (mark[v] != stamp) {
                        label(v, c, offset + a);
                        push(c, v);
                    } else if (c < dist[v]) {
                        dist[v] = c;
                        pred[v] = offset + a;
                        push(c, v);
                    } else if (c == dist[v] && wa > 0 && pred[v] > offset + a) {
                        // Of equal ways in, the lowest arc: a route is then a function of the weights alone, not of
                        // the order a search happened to relax them in, so two searches agree on it. Never over a
                        // free arc: two nodes could then each name the other.
                        pred[v] = offset + a;
                    }
                }
            }
            Arrays.sort(reached, 0, size);
        }

        private void push(long key, int node) {
            if (heapSize == heapKey.length) {
                heapKey = Arrays.copyOf(heapKey, heapSize * 2);
                heapNode = Arrays.copyOf(heapNode, heapSize * 2);
            }
            int i = heapSize++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heapKey[parent] <= key) {
                    break;
                }
                heapKey[i] = heapKey[parent];
                heapNode[i] = heapNode[parent];
                i = parent;
            }
            heapKey[i] = key;
            heapNode[i] = node;
        }

        private void pop() {
            long key = heapKey[--heapSize];
            int node = heapNode[heapSize];
            int i = 0;
            int half = heapSize >>> 1;
            while (i < half) {
                int child = 2 * i + 1;
                if (child + 1 < heapSize && heapKey[child + 1] < heapKey[child]) {
                    child++;
                }
                if (heapKey[child] >= key) {
                    break;
                }
                heapKey[i] = heapKey[child];
                heapNode[i] = heapNode[child];
                i = child;
            }
            heapKey[i] = key;
            heapNode[i] = node;
        }

        @Override
        public void hold(Metric m, Object source) {
            heldMetric = new WeakReference<>(m);
            heldSource = source;
        }

        @Override
        public boolean holds(Metric m, Object source) {
            WeakReference<Metric> held = heldMetric;
            return held != null && held.get() == m && source.equals(heldSource);
        }

        @Override
        public boolean reached(int rank) {
            return mark[rank] == stamp;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public int rankAt(int i) {
            return reached[i];
        }

        @Override
        public long dist(int rank) {
            return dist[rank];
        }

        @Override
        public int pred(int rank) {
            return pred[rank];
        }
    }
}
