package openrsc.nav.cache;

/** The stores the engine memoises into, each bounded by bytes; {@link NavCacheBudget} sizes each. */
public enum NavStore {
    /** Routing metrics, one per routing class: the transports it may use, its danger and its costs. */
    METRICS,

    /** Backward search labels toward a goal tile, area or target set, per routing class. */
    GOAL_LABELS,

    /**
     * Distance planes between a cluster's tiles and its nodes, per cluster, direction and danger. The
     * one store asked for as {@link NavCaches#persisted}.
     */
    CLUSTER_LEGS
}
