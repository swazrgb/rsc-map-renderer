package openrsc.gamedata.landscape;

/**
 * One 48x48-tile landscape sector as the server's {@code Tile} holds it, whichever container it was
 * decoded from. Every array is indexed {@code lx * REGION_SIZE + ly}, x-major. Wall and overlay bytes
 * are 1-based definition ids, 0 for none.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/Sector.java#L51">Sector.getTile</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L385">WorldLoader.loadSection</a>
 */
public final class RawSector {

    /** Tiles per sector edge. */
    public static final int REGION_SIZE = 48;

    public static final int SIZE = REGION_SIZE * REGION_SIZE;

    public final byte[] groundElevation = new byte[SIZE];
    public final byte[] groundTexture = new byte[SIZE];
    public final byte[] groundOverlay = new byte[SIZE];
    public final byte[] roofTexture = new byte[SIZE];
    public final byte[] horizontalWall = new byte[SIZE];
    public final byte[] verticalWall = new byte[SIZE];

    /**
     * A diagonal wall: {@code 1..11999} is a wall def id + 1 on one diagonal, {@code 12001..23999} is
     * one on the other (id + 12001); larger values are not walls.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L419">WorldLoader.loadSection</a>
     */
    public final int[] diagonalWalls = new int[SIZE];
}
