package openrsc.nav.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkloadsAgreeTest {

    private static final int ASKS = 4_000;

    @TempDir
    static Path dir;

    @BeforeAll
    static void mapIntoTempDir() {
        System.setProperty("navbench.dir", dir.toString());
    }

    @Test
    void everySettledRepresentationAnswersAsTheArraysDo() throws IOException {
        int count = 2 * Shape.BANKS + 3;
        Queries q = new Queries(count, 7);
        ArraySettled[] expected = arrays(SettledImpl.ARRAY_DIRECT.pool(count, 42));

        for (SettledImpl impl : SettledImpl.values()) {
            SettledView[] pool = impl.pool(count, 42);
            for (int k = 0; k < ASKS; k++) {
                int l = k & (Queries.LEGS - 1);
                assertEquals(
                        Workloads.composeTileArrays(
                                expected[q.picks[k]], q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts),
                        Workloads.composeTile(
                                pool[q.picks[k]], q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts),
                        impl + " composeTile at ask " + k);
                if (pool[q.picks[k]] instanceof PackedSettled p) {
                    assertEquals(
                            Workloads.composeTileArrays(
                                    expected[q.picks[k]],
                                    q.srcFirsts[k],
                                    q.leg1[l],
                                    q.leg1Time[l],
                                    q.anywhere,
                                    q.tCosts),
                            Workloads.composeTilePacked(
                                    p, q.srcFirsts[k], q.leg1[l], q.leg1Time[l], q.anywhere, q.tCosts),
                            impl + " composeTilePacked at ask " + k);
                }
                assertEquals(
                        Workloads.composeFromNodeArrays(expected, q.bankFirsts[k], q.nodes[k], q.bankSeed),
                        Workloads.composeFromNode(pool, q.bankFirsts[k], q.nodes[k], q.bankSeed),
                        impl + " composeFromNode at ask " + k);
            }

            ArraySettled want = new ArraySettled();
            SettledView got = impl.pool(1, 42)[0];
            assertEquals(Workloads.settleArrays(want, q), Workloads.settle(got, q), impl + " writes");
            for (int n = 0; n < Shape.NODES; n++) {
                assertEquals(want.dist[n], got.dist(n), impl + " dist " + n);
                assertEquals(want.time[n], got.time(n), impl + " time " + n);
                assertEquals(want.parent[n], got.parent(n), impl + " parent " + n);
                assertEquals(want.tag[n], got.tag(n), impl + " tag " + n);
            }
        }
    }

    @Test
    void everyPlaneRepresentationAnswersAsTheArraysDo() throws IOException {
        int count = 3;
        Queries q = new Queries(count, 7);
        PlaneView[] reference = PlaneImpl.ARRAY_DIRECT.pool(count, 42);
        int[] wantOut = new int[Shape.CLUSTER_NODES];
        int[] wantTimes = new int[Shape.CLUSTER_NODES];
        int[] gotOut = new int[Shape.CLUSTER_NODES];
        int[] gotTimes = new int[Shape.CLUSTER_NODES];

        for (PlaneImpl impl : PlaneImpl.values()) {
            PlaneView[] pool = impl.pool(count, 42);
            for (int k = 0; k < ASKS; k++) {
                for (int tiles : new int[] {1, 4}) {
                    Workloads.costsAtArrays((ArrayPlane) reference[q.picks[k]], q.cells, k, tiles, wantOut, wantTimes);
                    Workloads.costsAt(pool[q.picks[k]], q.cells, k, tiles, gotOut, gotTimes);
                    assertArrayEquals(wantOut, gotOut, impl + " costs at ask " + k + ", tiles " + tiles);
                    assertArrayEquals(wantTimes, gotTimes, impl + " times at ask " + k + ", tiles " + tiles);
                }
            }
        }
    }

    private static ArraySettled[] arrays(SettledView[] pool) {
        ArraySettled[] out = new ArraySettled[pool.length];
        for (int i = 0; i < pool.length; i++) {
            out[i] = (ArraySettled) pool[i];
        }
        return out;
    }
}
