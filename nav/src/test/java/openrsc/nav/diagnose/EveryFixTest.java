package openrsc.nav.diagnose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.nav.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EveryFixTest {

    private static final Route ROUTED = new Route(List.of(), 0, Map.of());

    @Test
    @DisplayName("every fix of a small trip is found, each minimal, and the listing says it is whole")
    void everyFixOfASmallTripIsFound() {
        int[] plans = new int[1];
        NoRouteReport.Search<String> s = NoRouteReport.everyFix(plans, chokepoints(2, 2, plans));

        assertTrue(s.complete());
        assertEquals(4, s.found().size(), "2 gates at each of 2 chokepoints: " + s.found());
        for (NoRouteReport.Found<String> f : s.found()) {
            assertEquals(2, f.changes().size(), "one gate per chokepoint: " + f.changes());
        }
    }

    @Test
    @DisplayName("a trip with more fixes than anyone can use stops at a dozen, and says so")
    void aTripWithTooManyFixesStopsAtADozen() {
        int[] plans = new int[1];
        NoRouteReport.Search<String> s = NoRouteReport.everyFix(plans, chokepoints(5, 5, plans));

        assertFalse(s.complete(), "3125 fixes exist; the listing must not claim to be whole");
        assertEquals(NoRouteReport.MAX_FIXES, s.found().size());
        Set<Set<String>> distinct = new HashSet<>();
        for (NoRouteReport.Found<String> f : s.found()) {
            assertEquals(5, f.changes().size(), "each listed fix is a real one: " + f.changes());
            distinct.add(Set.copyOf(f.changes()));
        }
        assertEquals(NoRouteReport.MAX_FIXES, distinct.size(), "and no fix is listed twice");
    }

    @Test
    @DisplayName("a search whose dead ends cost more than the budget stops at the budget")
    void deadEndsStopAtThePlanBudget() {
        int[] plans = new int[1];
        // Each probe costs a whole budget, so the search must stop after the first.
        NoRouteReport.Minimal<String> costly = forbidden -> {
            plans[0] += NoRouteReport.MAX_PLANS;
            return chokepoints(5, 5, new int[1]).avoiding(forbidden);
        };
        NoRouteReport.Search<String> s = NoRouteReport.everyFix(plans, costly);

        assertFalse(s.complete());
        assertEquals(1, s.found().size(), "one probe fits the budget: " + s.found());
    }

    /**
     * A trip through {@code k} chokepoints in a row, each crossed by any one of {@code m} gates named
     * {@code "c<chokepoint>g<gate>"}: it routes when every chokepoint has a lifted gate, so it has
     * m^k minimal fixes. A predicate rather than the map, since only the search's bookkeeping is
     * under test; each question asked counts as one plan in {@code plans}.
     */
    private static NoRouteReport.Minimal<String> chokepoints(int k, int m, int[] plans) {
        return forbidden -> {
            List<String> keep = new ArrayList<>();
            for (int c = 0; c < k; c++) {
                for (int g = 0; g < m; g++) {
                    String gate = "c" + c + "g" + g;
                    if (!forbidden.contains(gate)) {
                        keep.add(gate);
                    }
                }
            }
            plans[0]++;
            if (!routes(keep, k)) {
                return null;
            }
            int i = 0;
            while (i < keep.size()) {
                String dropped = keep.remove(i);
                plans[0]++;
                if (!routes(keep, k)) {
                    keep.add(i, dropped);
                    i++;
                }
            }
            return new NoRouteReport.Found<>(List.copyOf(keep), ROUTED);
        };
    }

    private static boolean routes(List<String> lifted, int k) {
        for (int c = 0; c < k; c++) {
            String prefix = "c" + c + "g";
            if (lifted.stream().noneMatch(g -> g.startsWith(prefix))) {
                return false;
            }
        }
        return true;
    }
}
