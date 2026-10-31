package openrsc.gamedata.world;

import java.util.Arrays;

/**
 * The 8x8-tile chunks around a player whose locs the server is streaming now. The server sends a
 * loc while its chunk is within {@code view_distance} chunks of the player's chunk on both axes, so
 * the live chunks are a square of side {@code 2r + 1} centred on the player's chunk. In a live chunk
 * the locs received are all there are; outside one, the stream says nothing.
 *
 * <p>The box is stored one chunk wider than the live square on every side. That outer ring is never
 * live, but holds a stamp for a chunk just outside the square, as when locs arrive before the move
 * that brings them into view. Immutable.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L219">Point.withinGridRange</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1065">GameStateUpdater.updateGameObjects</a>
 */
public final class ChunkBox {

    /** No box: nothing stored and nothing live. */
    public static final ChunkBox NONE = new ChunkBox(0, 0, 0, -1, new long[0]);

    private final int originCx;
    private final int originCy;
    private final int side;
    private final int liveRadius;
    private final long[] liveBits;

    private ChunkBox(int originCx, int originCy, int side, int liveRadius, long[] liveBits) {
        this.originCx = originCx;
        this.originCy = originCy;
        this.side = side;
        this.liveRadius = liveRadius;
        this.liveBits = liveBits;
    }

    /** The box for a player on tile {@code (x, y)}, live {@code gridRadius} chunks each way. */
    public static ChunkBox around(int x, int y, int gridRadius) {
        int storeRadius = gridRadius + 1;
        int side = 2 * storeRadius + 1;
        long[] live = new long[(side * side + 63) >> 6];
        for (int dy = 1; dy <= 2 * gridRadius + 1; dy++) {
            for (int dx = 1; dx <= 2 * gridRadius + 1; dx++) {
                int slot = dy * side + dx;
                live[slot >> 6] |= 1L << (slot & 63);
            }
        }
        return new ChunkBox((x >> 3) - storeRadius, (y >> 3) - storeRadius, side, gridRadius, live);
    }

    /** Whether {@link #around} would build this box's geometry for a player in chunk {@code (cx, cy)}. */
    public boolean centredOn(int cx, int cy, int gridRadius) {
        return side > 0
                && liveRadius == gridRadius
                && originCx + gridRadius + 1 == cx
                && originCy + gridRadius + 1 == cy;
    }

    /** This box with chunk {@code (cx, cy)} no longer live; {@code this} when it was not live. */
    public ChunkBox withoutChunk(int cx, int cy) {
        int slot = slotOfChunk(cx, cy);
        if (slot < 0 || (liveBits[slot >> 6] & (1L << (slot & 63))) == 0) {
            return this;
        }
        long[] live = liveBits.clone();
        live[slot >> 6] &= ~(1L << (slot & 63));
        return new ChunkBox(originCx, originCy, side, liveRadius, live);
    }

    /** This box's geometry with no chunk live. */
    public ChunkBox dormant() {
        return side == 0 ? this : new ChunkBox(originCx, originCy, side, liveRadius, new long[liveBits.length]);
    }

    /**
     * This box less every live chunk outside {@code previous}'s live square, for when the locs of
     * the chunks that just came into view may not all have arrived; {@code this} when none entered.
     */
    public ChunkBox withoutEntering(ChunkBox previous) {
        long[] live = null;
        for (int dy = 0; dy < side; dy++) {
            for (int dx = 0; dx < side; dx++) {
                int slot = dy * side + dx;
                if ((liveBits[slot >> 6] & (1L << (slot & 63))) == 0
                        || previous.inLiveRadius(originCx + dx, originCy + dy)) {
                    continue;
                }
                if (live == null) {
                    live = liveBits.clone();
                }
                live[slot >> 6] &= ~(1L << (slot & 63));
            }
        }
        return live == null ? this : new ChunkBox(originCx, originCy, side, liveRadius, live);
    }

    public boolean isLive(int x, int y) {
        return isLiveChunk(x >> 3, y >> 3);
    }

    public boolean isLiveChunk(int cx, int cy) {
        int slot = slotOfChunk(cx, cy);
        return slot >= 0 && (liveBits[slot >> 6] & (1L << (slot & 63))) != 0;
    }

    /** Whether chunk {@code (cx, cy)} lies in the live square, live or since cleared. */
    public boolean inLiveRadius(int cx, int cy) {
        if (side == 0) {
            return false;
        }
        int dx = cx - originCx;
        int dy = cy - originCy;
        return dx >= 1 && dy >= 1 && dx <= 2 * liveRadius + 1 && dy <= 2 * liveRadius + 1;
    }

    /** The box slot of tile {@code (x, y)}'s chunk, row-major from the origin; -1 outside the box. */
    public int slotOf(int x, int y) {
        return slotOfChunk(x >> 3, y >> 3);
    }

    /** The box slot of chunk {@code (cx, cy)}; -1 outside the box. */
    public int slotOfChunk(int cx, int cy) {
        int dx = cx - originCx;
        int dy = cy - originCy;
        return dx < 0 || dy < 0 || dx >= side || dy >= side ? -1 : dy * side + dx;
    }

    public int originCx() {
        return originCx;
    }

    public int originCy() {
        return originCy;
    }

    /** The stored side in chunks, {@code 2 * gridRadius + 3}; 0 for {@link #NONE}. */
    public int side() {
        return side;
    }

    public boolean isEmpty() {
        return side == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o == this
                || (o instanceof ChunkBox b
                        && originCx == b.originCx
                        && originCy == b.originCy
                        && side == b.side
                        && liveRadius == b.liveRadius
                        && Arrays.equals(liveBits, b.liveBits));
    }

    @Override
    public int hashCode() {
        return ((originCx * 31 + originCy) * 31 + side) * 31 + Arrays.hashCode(liveBits);
    }

    @Override
    public String toString() {
        return side == 0 ? "ChunkBox[none]" : "ChunkBox[" + originCx + "," + originCy + " side " + side + "]";
    }
}
