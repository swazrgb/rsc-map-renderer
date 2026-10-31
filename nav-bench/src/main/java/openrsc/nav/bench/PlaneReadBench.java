package openrsc.nav.bench;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Times reads off finished leg planes, drawn uniformly from {@code poolMb} of them: every node's
 * cost and time at one tile ({@code costsAt}), and the least over four tiles ({@code minOver4}),
 * for each {@link PlaneImpl}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
public class PlaneReadBench {

    @Param
    public PlaneImpl impl;

    @Param({"1024"})
    public int poolMb;

    private PlaneView[] pool;
    private ArrayPlane[] arrays;
    private Queries q;
    private final int[] out = new int[Shape.CLUSTER_NODES];
    private final int[] outTimes = new int[Shape.CLUSTER_NODES];
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        int count = (int) ((poolMb * (1L << 20)) / Shape.PLANE_BYTES);
        pool = impl.pool(count, 42);
        if (impl == PlaneImpl.ARRAY_DIRECT) {
            arrays = new ArrayPlane[count];
            for (int i = 0; i < count; i++) {
                arrays[i] = (ArrayPlane) pool[i];
            }
        }
        q = new Queries(count, 7);
    }

    @Benchmark
    public int costsAt() {
        return read(1);
    }

    @Benchmark
    public int minOver4() {
        return read(4);
    }

    private int read(int tiles) {
        int k = cursor++ & Queries.MASK;
        if (arrays != null) {
            return Workloads.costsAtArrays(arrays[q.picks[k]], q.cells, k, tiles, out, outTimes);
        }
        return Workloads.costsAt(pool[q.picks[k]], q.cells, k, tiles, out, outTimes);
    }
}
