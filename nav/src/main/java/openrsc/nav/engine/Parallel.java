package openrsc.nav.engine;

import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * The engine's one parallel loop, in a class of its own so that the browser build (nav-web), which
 * has a single thread, can substitute a sequential one.
 */
public final class Parallel {

    private Parallel() {}

    /** Runs {@code body} once for every index in {@code [0, n)}, in no order, across the common pool. */
    public static void forEach(int n, IntConsumer body) {
        IntStream.range(0, n).parallel().forEach(body);
    }
}
