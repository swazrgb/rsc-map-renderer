package openrsc.nav;

import org.jetbrains.annotations.Nullable;

/**
 * The answer to {@link Planner#plan}. {@link NoRoute} covers both a destination nothing reaches for
 * this account and one whose every route the loadout rules out; it does not say which
 * ({@link openrsc.nav.diagnose.NoRouteReport} does).
 */
public sealed interface PlanResult permits PlanResult.Planned, PlanResult.NoRoute {

    record Planned(Route route) implements PlanResult {}

    /**
     * @param limit the planner's own search limit that ended the search, when one did: then a route
     *     may exist that the search did not reach. Null when the search ran to completion.
     */
    record NoRoute(@Nullable String limit) implements PlanResult {
        public NoRoute() {
            this(null);
        }
    }
}
