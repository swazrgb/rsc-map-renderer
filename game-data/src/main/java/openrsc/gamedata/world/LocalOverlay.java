package openrsc.gamedata.world;

/**
 * Collision the server has streamed for locs, read on top of a {@link CollisionMap}'s planes; see
 * {@link CollisionMap#flags(int, int, LocalOverlay)} for how the two combine.
 */
public interface LocalOverlay {

    /**
     * The flags the stream put at {@code (x, y)}, in {@link CollisionMap}'s bit layout, or 0. Read
     * once per tile a search expands, so an implementation must not allocate.
     */
    int flags(int x, int y);

    /** Whether the server is streaming the chunk holding {@code (x, y)}, so its locs there are complete. */
    boolean isLive(int x, int y);
}
