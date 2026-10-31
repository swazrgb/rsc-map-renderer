package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TravelTimeTest {

    @Test
    void no_route_throws_when_read_as_a_duration() {
        assertFalse(TravelTime.NO_ROUTE.isReachable());
        assertThrows(NoRouteException.class, TravelTime.NO_ROUTE::deci);
        assertThrows(NoRouteException.class, TravelTime.NO_ROUTE::ticks);
        assertEquals(7, TravelTime.NO_ROUTE.ticksOrElse(7), "the caller names what no route means");
        assertFalse(TravelTime.NO_ROUTE.within(Integer.MAX_VALUE));
    }

    @Test
    void a_negative_engine_answer_is_no_route() {
        assertEquals(TravelTime.NO_ROUTE, TravelTime.ofDeci(-1));
        assertEquals(12, TravelTime.ofDeci(125).ticks(), "ticks round down");
        assertEquals(125, TravelTime.ofDeci(125).deci());
        assertThrows(IllegalArgumentException.class, () -> TravelTime.ofTicks(-1));
    }

    @Test
    void no_route_sorts_after_every_duration() {
        List<TravelTime> times = new ArrayList<>(List.of(
                TravelTime.NO_ROUTE, TravelTime.ofDeci(Integer.MAX_VALUE), TravelTime.ZERO, TravelTime.ofDeci(40)));
        Collections.sort(times);
        assertEquals(
                List.of(
                        TravelTime.ZERO,
                        TravelTime.ofDeci(40),
                        TravelTime.ofDeci(Integer.MAX_VALUE),
                        TravelTime.NO_ROUTE),
                times);
    }

    @Test
    void nearest_skips_no_route_and_is_empty_when_nothing_is_reachable() {
        assertEquals(
                OptionalInt.of(2),
                TravelTimes.ofDeci(new int[] {-1, 90, 30, 30}).nearest());
        assertEquals(
                OptionalInt.empty(), TravelTimes.ofDeci(new int[] {-1, -1, -1}).nearest());
    }

    @Test
    void a_built_batch_is_no_route_wherever_nothing_was_set() {
        TravelTimes built = TravelTimes.builder(3).set(1, TravelTime.ofDeci(50)).build();
        assertEquals(TravelTimes.ofDeci(new int[] {-1, 50, -1}), built);
        assertTrue(built.get(1).isReachable());
        assertEquals(TravelTimes.ofDeci(new int[] {-1, 50, -1, 10}), built.concat(TravelTimes.ofDeci(new int[] {10})));
    }
}
