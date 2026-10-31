package openrsc.nav.bench;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Compares three ways for a travel estimate to say "no route": an int sentinel, a value wrapping
 * one int (the shape of {@link openrsc.nav.TravelTime}), and a sealed interface of two records. The
 * estimates are stored as ints and wrapped on read, over a 250 by 60 matrix with about 5% unreachable,
 * in three consumer shapes: the nearest destination, a score with a penalty for no route, and a
 * reachable-within-budget gate. Run with {@code -prof gc}: the allocation rate is the result.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(2)
public class TravelTimeBench {

    static final int BOTS = 250;
    static final int DESTS = 60;
    static final int NO_ROUTE = -1;
    static final int PENALTY = 1_000_000;

    record Value(int raw) implements Comparable<Value> {
        static final Value UNREACHABLE = new Value(NO_ROUTE);

        static Value of(int raw) {
            return raw < 0 ? UNREACHABLE : new Value(raw);
        }

        boolean isReachable() {
            return raw >= 0;
        }

        int ticks() {
            if (raw < 0) {
                throw new IllegalStateException("no route");
            }
            return raw;
        }

        int orElse(int ifNoRoute) {
            return raw >= 0 ? raw : ifNoRoute;
        }

        @Override
        public int compareTo(Value o) {
            return Integer.compareUnsigned(raw, o.raw); // -1 compares as the largest: no route sorts last
        }
    }

    sealed interface Travel permits Ticks, NoRoute {
        static Travel of(int raw) {
            return raw < 0 ? NoRoute.INSTANCE : new Ticks(raw);
        }
    }

    record Ticks(int ticks) implements Travel {}

    record NoRoute() implements Travel {
        static final NoRoute INSTANCE = new NoRoute();
    }

    int[][] rows;
    int[] fit;
    int budget;

    @Setup
    public void setup() {
        SplittableRandom rnd = new SplittableRandom(42);
        rows = new int[BOTS][DESTS];
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                row[j] = rnd.nextInt(100) < 5 ? NO_ROUTE : 5 + rnd.nextInt(400);
            }
        }
        fit = new int[DESTS];
        for (int j = 0; j < DESTS; j++) {
            fit[j] = 200 + rnd.nextInt(800);
        }
        budget = 150;
    }

    @Benchmark
    public long nearestInt() {
        long sum = 0;
        for (int[] row : rows) {
            int best = -1;
            int bestT = Integer.MAX_VALUE;
            for (int j = 0; j < DESTS; j++) {
                int t = row[j];
                if (t >= 0 && t < bestT) {
                    bestT = t;
                    best = j;
                }
            }
            sum += best;
        }
        return sum;
    }

    /** Carries a value from one iteration to the next; the other value shapes drop each within one. */
    @Benchmark
    public long nearestValue() {
        long sum = 0;
        for (int[] row : rows) {
            int best = -1;
            Value bestT = Value.UNREACHABLE;
            for (int j = 0; j < DESTS; j++) {
                Value t = Value.of(row[j]);
                if (t.compareTo(bestT) < 0) {
                    bestT = t;
                    best = j;
                }
            }
            sum += best;
        }
        return sum;
    }

    @Benchmark
    public long nearestSealedSwitch() {
        long sum = 0;
        for (int[] row : rows) {
            int best = -1;
            int bestT = Integer.MAX_VALUE;
            for (int j = 0; j < DESTS; j++) {
                switch (Travel.of(row[j])) {
                    case Ticks(int t) -> {
                        if (t < bestT) {
                            bestT = t;
                            best = j;
                        }
                    }
                    case NoRoute n -> {}
                }
            }
            sum += best;
        }
        return sum;
    }

    @Benchmark
    public long nearestSealedInstanceof() {
        long sum = 0;
        for (int[] row : rows) {
            int best = -1;
            int bestT = Integer.MAX_VALUE;
            for (int j = 0; j < DESTS; j++) {
                if (Travel.of(row[j]) instanceof Ticks(int t) && t < bestT) {
                    bestT = t;
                    best = j;
                }
            }
            sum += best;
        }
        return sum;
    }

    @Benchmark
    public long scoreInt() {
        long sum = 0;
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                int t = row[j];
                sum += fit[j] - (t >= 0 ? t : PENALTY);
            }
        }
        return sum;
    }

    @Benchmark
    public long scoreValue() {
        long sum = 0;
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                sum += fit[j] - Value.of(row[j]).orElse(PENALTY);
            }
        }
        return sum;
    }

    @Benchmark
    public long scoreSealedSwitch() {
        long sum = 0;
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                int t =
                        switch (Travel.of(row[j])) {
                            case Ticks(int ticks) -> ticks;
                            case NoRoute n -> PENALTY;
                        };
                sum += fit[j] - t;
            }
        }
        return sum;
    }

    @Benchmark
    public long gateInt() {
        long n = 0;
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                int t = row[j];
                if (t >= 0 && t <= budget) {
                    n++;
                }
            }
        }
        return n;
    }

    @Benchmark
    public long gateValue() {
        long n = 0;
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                Value t = Value.of(row[j]);
                if (t.isReachable() && t.ticks() <= budget) {
                    n++;
                }
            }
        }
        return n;
    }

    @Benchmark
    public long gateSealedInstanceof() {
        long n = 0;
        for (int[] row : rows) {
            for (int j = 0; j < DESTS; j++) {
                if (Travel.of(row[j]) instanceof Ticks(int t) && t <= budget) {
                    n++;
                }
            }
        }
        return n;
    }
}
