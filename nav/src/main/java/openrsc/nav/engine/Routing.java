package openrsc.nav.engine;

import java.util.List;
import openrsc.bot.api.Nullable;

/**
 * What the engine routes over a {@link ClusterGraph} with: its nodes, then one cast source per teleport region
 * ({@link #firstSource}), each with a rank that labels are indexed by; the metrics that weigh it for a routing
 * class; and the searches that query it. Safe for concurrent use; a {@link Search} is not.
 */
interface Routing {

    int nodeCount();

    /** The first cast source's node, one past the graph's last node. */
    int firstSource();

    int rankOf(int node);

    /**
     * Per rank, its row in a metric's bank labels ({@link Metric#toBankLabels}), -1 for a rank with none. Shared:
     * never write it.
     */
    int[] bankSlots();

    /** The end of edge {@code edge}, a half's {@link Search.Half#pred}, that lies toward the seeds of that half. */
    int tail(int edge);

    /**
     * The metric of the class that may use the transports {@code canUse} marks, at {@code tCosts}, under
     * {@code danger}; the class keeps all three, so none may change afterwards. {@code parent}, when not null, is
     * the metric of a class with the same field and costs that may use {@code parentCanUse}, which an
     * implementation may start from.
     */
    Metric customize(
            DangerField danger,
            boolean[] canUse,
            int[] tCosts,
            @Nullable Metric parent,
            boolean @Nullable [] parentCanUse);

    /** A new search, for one thread. */
    Search search();

    /** A one-line summary for logs. The format is not fixed. */
    String describe();

    /** A one-line summary of the work done so far, for logs. The format is not fixed. */
    String stats();

    /** Builds the routing of a graph. */
    interface Builder {
        /**
         * {@code fields} holds every danger field a metric may be customized under, {@code gated} whether each
         * transport's usability reads anything of the account, {@code baseCosts} the costs most classes pay.
         */
        Routing build(ClusterGraph graph, List<DangerField> fields, boolean[] gated, int[] baseCosts);
    }
}
