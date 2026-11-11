package openrsc.nav.engine;

import java.util.List;
import openrsc.bot.api.Nullable;

/**
 * One thread's query state over a {@link Routing}: a {@link #forward} half from source seeds and a
 * {@link #backward} half from target seeds. A route's cost is the least sum of the halves' labels over the ranks
 * both reach; the halves are independent, so one forward half can be met against many backward ones, and a
 * backward half's labels can be kept and met later.
 *
 * <p>A chain is a route as {@code {node, tag}} pairs in travel order, each tag the arc walked into its node:
 * {@link Engine#TAG_SEED} at the root, or the teleport's transport when the route opens with a cast from the start
 * tile, rooted at the landing. A cast source is never a link: a cast part way is the node cast from, then the
 * landing tagged with the teleport. Of equal routes, a chain meets at the lowest rank.
 *
 * <p>Not thread-safe; reused query after query.
 */
interface Search {

    Half forward();

    Half backward();

    /**
     * The forward half's cheapest route to a goal whose backward labels were kept: its reached ranks ascending,
     * each one's cost to the goal and its {@link Half#pred}. Null when nothing meets.
     */
    @Nullable
    List<int[]> chainToLabels(Metric m, int[] ranks, long[] costs, int[] preds);

    /** The forward half's cheapest route to bank {@code bank}; null when it reaches none. */
    @Nullable
    List<int[]> chainToBank(Metric m, int bank);

    /** The cheapest route from bank {@code bank} to a goal kept as labels; neither half is touched. */
    @Nullable
    List<int[]> chainFromBank(Metric m, int bank, int[] ranks, long[] costs, int[] preds);

    /** One direction's labels: the packed cost from its seeds, or to them for a backward half. */
    interface Half {

        void reset();

        /**
         * Seeds {@code node} at {@code cost}, the lower of two seeds on one node kept; true when this one is its
         * label now.
         */
        boolean seed(int node, long cost);

        /** Labels every rank the seeds reach under {@code m}. */
        void run(Metric m);

        /**
         * Vouches that this half, as just run under {@code m}, was seeded from {@code source} alone. Holds
         * {@code m} weakly: a thread's halves outlive its queries, and never keep a metric alive.
         */
        void hold(Metric m, Object source);

        /** Whether the run {@link #hold} vouched for still stands: nothing has reset or seeded the half since. */
        boolean holds(Metric m, Object source);

        /** Whether the last run reached {@code rank}; its {@link #dist} may still be {@link PackedCost#INF}. */
        boolean reached(int rank);

        /** How many ranks the last run reached. */
        int size();

        /** The {@code i}-th reached rank, ascending. */
        int rankAt(int i);

        long dist(int rank);

        /** The edge that last improved {@code rank}'s label; negative at a seed and where none did. */
        int pred(int rank);
    }
}
