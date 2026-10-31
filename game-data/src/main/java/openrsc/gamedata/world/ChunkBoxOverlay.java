package openrsc.gamedata.world;

/**
 * A frozen copy of a {@link WallOverlay}: its stamps and its box at one moment. Immutable apart from
 * a cache filled on demand, so it may be shared across threads.
 */
public final class ChunkBoxOverlay implements LocalOverlay {

    /** No box: every tile reads 0 and none is live. */
    public static final ChunkBoxOverlay EMPTY = new ChunkBoxOverlay(ChunkBox.NONE, null);

    private final ChunkBox box;

    private final byte[] tiles;

    private ChunkBoxOverlay(ChunkBox box, byte[] tiles) {
        this.box = box;
        this.tiles = tiles;
    }

    static ChunkBoxOverlay of(ChunkBox box, byte[] tiles) {
        if (box.isEmpty()) {
            return EMPTY;
        }
        return new ChunkBoxOverlay(box, tiles);
    }

    @Override
    public int flags(int x, int y) {
        int slot = box.slotOf(x, y);
        if (slot < 0 || tiles == null) {
            return 0;
        }
        return tiles[(slot << 6) | ((y & 7) << 3) | (x & 7)] & 0xFF;
    }

    @Override
    public boolean isLive(int x, int y) {
        return box.isLive(x, y);
    }

    // Per box slot: 0 not yet asked, 1 the chunk reads as the map, 2 it does not. Two readers racing
    // compute the same answer.
    private volatile byte[] agreement;

    /**
     * Whether every tile within one step of {@code (x, y)} reads through this overlay exactly as
     * {@code map} has it, so a step test from there may use the map's precomputed answer.
     */
    boolean agreesAround(CollisionMap map, int x, int y) {
        for (int cy = (y - 1) >> 3; cy <= (y + 1) >> 3; cy++) {
            for (int cx = (x - 1) >> 3; cx <= (x + 1) >> 3; cx++) {
                if (!chunkAgrees(map, cx, cy)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean chunkAgrees(CollisionMap map, int cx, int cy) {
        int slot = box.slotOfChunk(cx, cy);
        if (slot < 0) {
            return true;
        }
        byte[] known = agreement;
        if (known == null) {
            known = new byte[box.side() * box.side()];
            agreement = known;
        }
        if (known[slot] != 0) {
            return known[slot] == 1;
        }
        boolean agrees = true;
        for (int ty = cy << 3; agrees && ty < (cy << 3) + 8; ty++) {
            for (int tx = cx << 3; tx < (cx << 3) + 8; tx++) {
                // Step tests never read PROJECTILE_PASS, and the map's own planes never carry it.
                if ((map.flags(tx, ty, this) & ~CollisionMap.PROJECTILE_PASS) != map.flags(tx, ty)) {
                    agrees = false;
                    break;
                }
            }
        }
        known[slot] = (byte) (agrees ? 1 : 2);
        return agrees;
    }
}
