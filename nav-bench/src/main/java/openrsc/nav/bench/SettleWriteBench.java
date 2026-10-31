package openrsc.nav.bench;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
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
 * Times one settle's writes ({@link Workloads#settle}) for each {@link SettledImpl}.
 *
 * <p>{@code reuse} writes into the same storage each time, its pages hot and already dirty.
 * {@code freshSlot} writes into new storage each time, what building a cache entry costs: a new heap
 * object, or an untouched slot of a mapped file where every page faults on its first write. It runs
 * single-shot in batches of {@link #BATCH}, so the mapped region can be sized never to wrap.
 */
@State(Scope.Thread)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
public class SettleWriteBench {

    static final int BATCH = 256;
    private static final int WARMUP_BATCHES = 10;
    private static final int MEASURED_BATCHES = 20;

    @Param({
        "ARRAY_DIRECT",
        "ARRAY_VIEW",
        "HEAP_PLANAR",
        "HEAP_PACKED",
        "NATIVE_PACKED",
        "MAPPED_PLANAR",
        "MAPPED_PACKED"
    })
    public SettledImpl impl;

    private Queries q;
    private SettledView scratch;
    private MemorySegment slots;
    private long nextSlot;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        q = new Queries(1, 11);
        scratch = impl.pool(1, 42)[0];
        if (impl == SettledImpl.MAPPED_PLANAR || impl == SettledImpl.MAPPED_PACKED) {
            long needed = (long) (WARMUP_BATCHES + MEASURED_BATCHES + 2) * BATCH * Shape.SETTLED_BYTES;
            slots = Regions.mapped("slots", needed);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = 3, time = 2)
    @Measurement(iterations = 5, time = 2)
    public int reuse() {
        return scratch instanceof ArraySettled a && impl == SettledImpl.ARRAY_DIRECT
                ? Workloads.settleArrays(a, q)
                : Workloads.settle(scratch, q);
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @Warmup(iterations = WARMUP_BATCHES, batchSize = BATCH)
    @Measurement(iterations = MEASURED_BATCHES, batchSize = BATCH)
    public Object freshSlot() {
        return switch (impl) {
            case ARRAY_DIRECT -> {
                ArraySettled s = new ArraySettled();
                Workloads.settleArrays(s, q);
                yield s;
            }
            case ARRAY_VIEW -> {
                SettledView s = new ArraySettled();
                Workloads.settle(s, q);
                yield s;
            }
            case HEAP_PLANAR -> {
                SettledView s = new PlanarSettled(Regions.heap(Shape.SETTLED_BYTES), 0);
                Workloads.settle(s, q);
                yield s;
            }
            case HEAP_PACKED -> {
                SettledView s = new PackedSettled(Regions.heap(Shape.SETTLED_BYTES), 0);
                Workloads.settle(s, q);
                yield s;
            }
            case MAPPED_PLANAR -> {
                SettledView s = new PlanarSettled(slots, takeSlot());
                Workloads.settle(s, q);
                yield s;
            }
            case MAPPED_PACKED -> {
                SettledView s = new PackedSettled(slots, takeSlot());
                Workloads.settle(s, q);
                yield s;
            }
            default -> {
                // Native memory is zeroed, so touched, when allocated: it has no untouched slot.
                yield Workloads.settle(scratch, q);
            }
        };
    }

    private long takeSlot() {
        long at = nextSlot;
        nextSlot += Shape.SETTLED_BYTES;
        if (nextSlot > slots.byteSize()) {
            throw new IllegalStateException("slot region exhausted — size it for more batches");
        }
        return at;
    }
}
