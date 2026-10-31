package openrsc.gamedata.geometry;

/**
 * A world tile. {@code x} grows to the west and {@code y} to the south, and the
 * world's four floors are stacked along {@code y}, one {@link #FLOOR_HEIGHT} apart.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L531">WorldLoader.loadWorld</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L472">Formulae.getHeight</a>
 */
public record Point(int x, int y) implements Area, Comparable<Point> {

    /** Tiles along {@code y} from one floor to the next. */
    public static final int FLOOR_HEIGHT = 944;

    /** No position: never a real tile. */
    public static final Point UNKNOWN = new Point(Integer.MIN_VALUE, Integer.MIN_VALUE);

    public boolean known() {
        return !equals(UNKNOWN);
    }

    public static Point of(int x, int y) {
        return new Point(x, y);
    }

    @Override
    public int minX() {
        return x;
    }

    @Override
    public int minY() {
        return y;
    }

    @Override
    public int maxX() {
        return x;
    }

    @Override
    public int maxY() {
        return y;
    }

    /** {@code y} with the floor offset taken off: the row on this point's floor. */
    public int localY() {
        return Math.floorMod(y, FLOOR_HEIGHT);
    }

    /** Steps, in king moves, between two tiles. */
    public static int chebyshev(int ax, int ay, int bx, int by) {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    /**
     * Straight-line distance in tiles, truncated: the metric of the server's {@code withinRange}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L408">Point.getDistancePythagoras</a>
     */
    public static int pythagoras(int ax, int ay, int bx, int by) {
        int dx = ax - bx;
        int dy = ay - by;
        return (int) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public int compareTo(Point o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(y, o.y);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
