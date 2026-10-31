package openrsc.nav.bench;

import static openrsc.nav.bench.Shape.UNREACHED;

import java.util.SplittableRandom;

/**
 * Query streams drawn up front, so a benchmark measures storage access rather than a random number
 * generator. Picks are uniform over the pool, with no hot set: the worst case for every cache level
 * and for the page cache.
 */
final class Queries {

    static final int SIZE = 1 << 16;
    static final int MASK = SIZE - 1;
    static final int LEGS = 256;
    /** Relaxations in one settle. */
    static final int EDGES = 8 * Shape.NODES;

    final int[] picks = new int[SIZE];
    final int[] bankFirsts = new int[SIZE];
    final int[] srcFirsts = new int[SIZE];
    final int[] nodes = new int[SIZE];
    final int[] cells = new int[SIZE];
    final int[][] leg1 = new int[LEGS][Shape.CLUSTER_NODES];
    final int[][] leg1Time = new int[LEGS][Shape.CLUSTER_NODES];
    final int[] anywhere = new int[Shape.ANYWHERE];
    final int[] tCosts = new int[Shape.ANYWHERE];
    final int[] bankSeed = new int[Shape.BANKS];
    final int[] edgeNode = new int[EDGES];
    final int[] edgeDist = new int[EDGES];
    final int[] edgeTime = new int[EDGES];
    final short[] edgeFrom = new short[EDGES];
    final short[] edgeTag = new short[EDGES];

    Queries(int poolSize, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        int bankRange = Math.max(1, poolSize - Shape.BANKS + 1);
        for (int k = 0; k < SIZE; k++) {
            picks[k] = r.nextInt(poolSize);
            bankFirsts[k] = r.nextInt(bankRange);
            srcFirsts[k] = r.nextInt(Shape.NODES - Shape.CLUSTER_NODES + 1);
            nodes[k] = r.nextInt(Shape.NODES);
            cells[k] = r.nextInt(Shape.CELLS);
        }
        for (int l = 0; l < LEGS; l++) {
            for (int m = 0; m < Shape.CLUSTER_NODES; m++) {
                boolean reached = r.nextInt(10) != 0;
                leg1[l][m] = reached ? r.nextInt(5_000) : UNREACHED;
                leg1Time[l][m] = reached ? leg1[l][m] : UNREACHED;
            }
        }
        for (int a = 0; a < Shape.ANYWHERE; a++) {
            anywhere[a] = r.nextInt(Shape.NODES);
            tCosts[a] = 50 + r.nextInt(500);
        }
        for (int b = 0; b < Shape.BANKS; b++) {
            bankSeed[b] = r.nextInt(5_000);
        }
        for (int e = 0; e < EDGES; e++) {
            edgeNode[e] = r.nextInt(Shape.NODES);
            edgeDist[e] = r.nextInt(1_000_000);
            edgeTime[e] = edgeDist[e] / 2 + r.nextInt(1_000);
            edgeFrom[e] = (short) r.nextInt(Shape.NODES);
            edgeTag[e] = (short) r.nextInt(2229);
        }
    }
}
