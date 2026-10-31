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
 * Times pricing reads against settles drawn uniformly from {@code poolMb} of them, for each
 * {@link SettledImpl}: a tile against one settle ({@code composeTile}), and a node against
 * {@link Shape#BANKS} settles in a row ({@code composeFromNode}).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
public class SurfaceReadBench {

    @Param
    public SettledImpl impl;

    @Param({"1024"})
    public int poolMb;

    private SettledView[] pool;
    private ArraySettled[] arrays;
    private Queries q;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        int count = (int) ((poolMb * (1L << 20)) / Shape.SETTLED_BYTES);
        pool = impl.pool(count, 42);
        if (impl == SettledImpl.ARRAY_DIRECT) {
            arrays = new ArraySettled[count];
            for (int i = 0; i < count; i++) {
                arrays[i] = (ArraySettled) pool[i];
            }
        }
        q = new Queries(count, 7);
    }

    @Benchmark
    public long composeTile() {
        int k = cursor++ & Queries.MASK;
        int l = k & (Queries.LEGS - 1);
        if (arrays != null) {
            return Workloads.composeTileArrays(
                    arrays[q.picks[k]], q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts);
        }
        return Workloads.composeTile(pool[q.picks[k]], q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts);
    }

    /**
     * {@link #composeTile} with the layout picked once per call, before the loop, so every accessor
     * inside sees one receiver type. Against {@code MIXED} it separates per-read dispatch from the
     * rest of what a mixed pool costs.
     */
    @Benchmark
    public long composeTileSplit() {
        int k = cursor++ & Queries.MASK;
        int l = k & (Queries.LEGS - 1);
        SettledView s = pool[q.picks[k]];
        if (s instanceof ArraySettled a) {
            return Workloads.composeTileArrays(a, q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts);
        }
        if (s instanceof PackedSettled p) {
            return Workloads.composeTilePacked(p, q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts);
        }
        return Workloads.composeTile(s, q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts);
    }

    @Benchmark
    public long composeFromNode() {
        int k = cursor++ & Queries.MASK;
        if (arrays != null) {
            return Workloads.composeFromNodeArrays(arrays, q.bankFirsts[k], q.nodes[k], q.bankSeed);
        }
        return Workloads.composeFromNode(pool, q.bankFirsts[k], q.nodes[k], q.bankSeed);
    }
}
