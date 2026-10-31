package openrsc.gamedata.world;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live overlay: the collision of the locs the server has streamed into one {@link ChunkBox},
 * changed as locs are reported added and removed. Each add or remove sets or clears the bits the
 * server's {@code World} sets or clears for that loc. A stamp outside the box is dropped, with a
 * warning logged the first time.
 *
 * <p>Not thread-safe. {@link #snapshot} gives other readers an immutable copy.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L489">World.registerGameObject</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L771">World.unregisterGameObject</a>
 */
public final class WallOverlay implements LocalOverlay {

    private static final Logger LOG = LoggerFactory.getLogger(WallOverlay.class);

    private byte[] tiles;

    private ChunkBox box = ChunkBox.NONE;

    private boolean warnedOutOfBox;

    @Override
    public int flags(int x, int y) {
        int slot = slotOf(x, y);
        if (slot < 0 || tiles == null) {
            return 0;
        }
        return tiles[flatIndex(slot, x, y)] & 0xFF;
    }

    @Override
    public boolean isLive(int x, int y) {
        return box.isLive(x, y);
    }

    /**
     * A boundary at {@code (x, y)}: dir 0 walls the tile's north edge, 1 its east edge, 2 and 3 are
     * the two diagonals; any other dir is ignored. On the server only a boundary of door type 1
     * collides; this stamps whatever it is given.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L546">World.registerGameObject</a>
     */
    public void addBoundary(int x, int y, int dir) {
        switch (dir) {
            case 0 -> {
                or(x, y, CollisionMap.WALL_NORTH);
                or(x, y - 1, CollisionMap.WALL_SOUTH);
            }
            case 1 -> {
                or(x, y, CollisionMap.WALL_EAST);
                or(x - 1, y, CollisionMap.WALL_WEST);
            }
            case 2 -> or(x, y, CollisionMap.FULL_BLOCK_A);
            case 3 -> or(x, y, CollisionMap.FULL_BLOCK_B);
            default -> {
                /* ignore */
            }
        }
    }

    public void removeBoundary(int x, int y, int dir) {
        switch (dir) {
            case 0 -> {
                andNot(x, y, CollisionMap.WALL_NORTH);
                andNot(x, y - 1, CollisionMap.WALL_SOUTH);
            }
            case 1 -> {
                andNot(x, y, CollisionMap.WALL_EAST);
                andNot(x - 1, y, CollisionMap.WALL_WEST);
            }
            case 2 -> andNot(x, y, CollisionMap.FULL_BLOCK_A);
            case 3 -> andNot(x, y, CollisionMap.FULL_BLOCK_B);
            default -> {
                /* ignore */
            }
        }
    }

    /**
     * A def-type 1 (solid) scenery: {@link CollisionMap#FULL_BLOCK_C} over its footprint,
     * {@code defW} by {@code defH} tiles from the origin, turned when {@code dir} is not 0 or 4.
     */
    public void addSceneryFullBlock(int originX, int originY, int dir, int defW, int defH) {
        applyFullBlock(originX, originY, dir, defW, defH, true);
    }

    /**
     * Marks a projectile-clip scenery's tiles {@link CollisionMap#PROJECTILE_PASS}: its footprint
     * and, unless {@code fullBlock}, the one neighbour {@code dir} faces.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L593">World.handleProjectileClipAllowance</a>
     */
    public void addProjectilePass(int originX, int originY, int dir, int defW, int defH, boolean fullBlock) {
        applyProjectilePass(originX, originY, dir, defW, defH, fullBlock, true);
    }

    public void removeProjectilePass(int originX, int originY, int dir, int defW, int defH, boolean fullBlock) {
        applyProjectilePass(originX, originY, dir, defW, defH, fullBlock, false);
    }

    private void applyProjectilePass(
            int originX, int originY, int dir, int defW, int defH, boolean fullBlock, boolean stamp) {
        int w = defW, h = defH;
        if (dir != 0 && dir != 4) {
            int t = w;
            w = h;
            h = t;
        }
        for (int x = originX; x < originX + w; x++) {
            for (int y = originY; y < originY + h; y++) {
                pass(x, y, stamp);
                if (fullBlock) {
                    continue;
                }
                switch (dir) {
                    case 0 -> pass(x - 1, y, stamp);
                    case 2 -> pass(x, y + 1, stamp);
                    case 4 -> pass(x + 1, y, stamp);
                    case 6 -> pass(x, y - 1, stamp);
                    default -> {}
                }
            }
        }
    }

    private void pass(int x, int y, boolean stamp) {
        if (stamp) {
            or(x, y, CollisionMap.PROJECTILE_PASS);
        } else {
            andNot(x, y, CollisionMap.PROJECTILE_PASS);
        }
    }

    public void removeSceneryFullBlock(int originX, int originY, int dir, int defW, int defH) {
        applyFullBlock(originX, originY, dir, defW, defH, false);
    }

    /**
     * A def-type 2 (wall) scenery such as a gate: the edge {@code dir} names on every footprint tile,
     * and the matching edge on the tile across it.
     */
    public void addScenery(int originX, int originY, int dir, int defW, int defH) {
        applyScenery(originX, originY, dir, defW, defH, true);
    }

    public void removeScenery(int originX, int originY, int dir, int defW, int defH) {
        applyScenery(originX, originY, dir, defW, defH, false);
    }

    /** Drops every stamp in chunk {@code (chunkX, chunkZ)} and makes it not live until the box is rebuilt. */
    public void clearChunk(int chunkX, int chunkZ) {
        int slot = box.slotOfChunk(chunkX, chunkZ);
        if (slot < 0) {
            return;
        }
        if (tiles != null) {
            Arrays.fill(tiles, slot << 6, (slot << 6) + 64, (byte) 0);
        }
        box = box.withoutChunk(chunkX, chunkZ);
    }

    /**
     * Adopts {@code next} as the box. Stamps in chunks both boxes store are kept; the rest are
     * forgotten, since the server no longer streams them.
     */
    public void moveTo(ChunkBox next) {
        if (next.originCx() == box.originCx() && next.originCy() == box.originCy() && next.side() == box.side()) {
            box = next;
            return;
        }
        int side = next.side();
        byte[] carried = null;
        if (tiles != null) {
            for (int dy = 0; dy < side; dy++) {
                for (int dx = 0; dx < side; dx++) {
                    int old = box.slotOfChunk(next.originCx() + dx, next.originCy() + dy);
                    if (old < 0) {
                        continue;
                    }
                    int oBase = old << 6;
                    boolean any = false;
                    for (int i = 0; i < 64; i++) {
                        if (tiles[oBase + i] != 0) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    if (carried == null) {
                        carried = new byte[side * side * 64];
                    }
                    System.arraycopy(tiles, oBase, carried, ((dy * side + dx) << 6), 64);
                }
            }
        }
        tiles = carried;
        box = next;
    }

    public ChunkBoxOverlay snapshot() {
        if (box.isEmpty()) {
            return ChunkBoxOverlay.EMPTY;
        }
        return ChunkBoxOverlay.of(box, tiles == null ? null : tiles.clone());
    }

    private void applyFullBlock(int dx, int dy, int dir, int defW, int defH, boolean stamp) {
        int w = defW, h = defH;
        if (dir != 0 && dir != 4) {
            int t = w;
            w = h;
            h = t;
        }
        for (int x = dx; x < dx + w; x++) {
            for (int y = dy; y < dy + h; y++) {
                if (stamp) {
                    or(x, y, CollisionMap.FULL_BLOCK_C);
                } else {
                    andNot(x, y, CollisionMap.FULL_BLOCK_C);
                }
            }
        }
    }

    private void applyScenery(int dx, int dy, int dir, int defW, int defH, boolean stamp) {
        int w = defW, h = defH;
        if (dir != 0 && dir != 4) {
            int t = w;
            w = h;
            h = t;
        }
        for (int x = dx; x < dx + w; x++) {
            for (int y = dy; y < dy + h; y++) {
                switch (dir) {
                    case 0 -> {
                        edge(x, y, CollisionMap.WALL_EAST, stamp);
                        edge(x - 1, y, CollisionMap.WALL_WEST, stamp);
                    }
                    case 2 -> {
                        edge(x, y, CollisionMap.WALL_SOUTH, stamp);
                        edge(x, y + 1, CollisionMap.WALL_NORTH, stamp);
                    }
                    case 4 -> {
                        edge(x, y, CollisionMap.WALL_WEST, stamp);
                        edge(x + 1, y, CollisionMap.WALL_EAST, stamp);
                    }
                    case 6 -> {
                        edge(x, y, CollisionMap.WALL_NORTH, stamp);
                        edge(x, y - 1, CollisionMap.WALL_SOUTH, stamp);
                    }
                    default -> {}
                }
            }
        }
    }

    private void edge(int x, int y, int mask, boolean stamp) {
        if (stamp) {
            or(x, y, mask);
        } else {
            andNot(x, y, mask);
        }
    }

    private void or(int x, int y, int mask) {
        int slot = slotOf(x, y);
        if (slot < 0) {
            noteOutOfBox(x, y);
            return;
        }
        if (tiles == null) {
            tiles = new byte[box.side() * box.side() * 64];
        }
        int i = flatIndex(slot, x, y);
        tiles[i] = (byte) ((tiles[i] & 0xFF) | (mask & 0xFF));
    }

    private void andNot(int x, int y, int mask) {
        int slot = slotOf(x, y);
        if (slot < 0 || tiles == null) {
            return;
        }
        int i = flatIndex(slot, x, y);
        tiles[i] = (byte) ((tiles[i] & 0xFF) & ~mask);
    }

    private void noteOutOfBox(int x, int y) {
        if (!warnedOutOfBox) {
            warnedOutOfBox = true;
            LOG.warn(
                    "wall overlay: stamp at ({},{}) fell outside the streamed box"
                            + " (centre chunk {},{}, side {}) — dropped. The box should cover everything the server"
                            + " streams; if this fires, the box is not following the bot.",
                    x,
                    y,
                    box.originCx() + box.side() / 2,
                    box.originCy() + box.side() / 2,
                    box.side());
        }
    }

    private int slotOf(int x, int y) {
        return box.slotOf(x, y);
    }

    private static int localIndex(int x, int y) {
        return ((y & 7) << 3) | (x & 7);
    }

    private static int flatIndex(int slot, int x, int y) {
        return (slot << 6) | localIndex(x, y);
    }
}
