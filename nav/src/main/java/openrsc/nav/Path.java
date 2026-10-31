package openrsc.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A trip as the tiles it stands on, in order, from {@link Pathfinder#findPath}. Step 0 is the start
 * and the last step the goal. A step may repeat the one before (a tick spent waiting).
 *
 * <p>A hop step is a transport's landing; the step before it is the entry it was taken from. Hop
 * and bank-visit step indexes ascend.
 */
public class Path {

    private static final int[] NO_BANK_VISITS = new int[0];

    private final int width;
    private final int[] tileIds;
    private final int[] hopAt;
    private final Transport[] hops;
    private int[] bankAt = NO_BANK_VISITS;

    private int bankCount;

    /**
     * @param tileIds each step's tile as {@code y * width + x}; not empty
     * @param hopAt the hop steps, ascending, parallel to {@code hops}
     */
    public Path(int width, int[] tileIds, int[] hopAt, Transport[] hops) {
        if (tileIds == null || tileIds.length == 0) {
            throw new IllegalArgumentException("tileIds required");
        }
        if (hopAt.length != hops.length) {
            throw new IllegalArgumentException("hopAt and hops length mismatch");
        }
        this.width = width;
        this.tileIds = tileIds;
        this.hopAt = hopAt;
        this.hops = hops;
    }

    public int size() {
        return tileIds.length;
    }

    public int x(int i) {
        int t = tileIds[i];
        return t - (t / width) * width;
    }

    public int y(int i) {
        return tileIds[i] / width;
    }

    public int destX() {
        return x(tileIds.length - 1);
    }

    public int destY() {
        return y(tileIds.length - 1);
    }

    public List<int[]> tiles() {
        List<int[]> out = new ArrayList<>(tileIds.length);
        for (int i = 0; i < tileIds.length; i++) {
            out.add(new int[] {x(i), y(i)});
        }
        return out;
    }

    public int hopCount() {
        return hopAt.length;
    }

    /** The transport of the {@code k}th hop, {@code 0 <= k < hopCount()}. */
    public Transport hopTransportAt(int k) {
        return hops[k];
    }

    public boolean isHopAt(int i) {
        if (hopAt.length == 0) {
            return false;
        }
        return Arrays.binarySearch(hopAt, i) >= 0;
    }

    /** The transport landing on step {@code i}, or null when step {@code i} is not a hop. */
    public Transport hopAt(int i) {
        if (hopAt.length == 0) {
            return null;
        }
        int k = Arrays.binarySearch(hopAt, i);
        return k >= 0 ? hops[k] : null;
    }

    /** The x of the entry the hop landing on step {@code i} is taken from; meaningful only at a hop step. */
    public int hopEntryX(int i) {
        return x(i - 1);
    }

    /** The y of the entry the hop landing on step {@code i} is taken from; meaningful only at a hop step. */
    public int hopEntryY(int i) {
        return y(i - 1);
    }

    public boolean isBankVisitAt(int i) {
        if (bankCount == 0) {
            return false;
        }
        return Arrays.binarySearch(bankAt, 0, bankCount, i) >= 0;
    }

    /** Marks step {@code i} as a bank visit. Steps are marked in ascending order. */
    public void markBankVisit(int i) {
        if (bankCount == bankAt.length) {
            bankAt = Arrays.copyOf(bankAt, Math.max(4, bankAt.length * 2));
        }
        bankAt[bankCount++] = i;
    }
}
