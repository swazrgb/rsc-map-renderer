package openrsc.gamedata.geometry;

/**
 * An inclusive rectangle of world tiles, {@code [minX..maxX] × [minY..maxY]}; a {@link Point} is the
 * 1×1 case. Every operation derives from the four bounds. Distances and overlaps compare raw
 * coordinates, so they mean something only between areas on the same {@link #floor()}.
 */
public sealed interface Area extends Dest permits Point, Rect {

    int minX();

    int minY();

    int maxX();

    int maxY();

    /** The floor this area's {@code minY} is on: 0 for the ground, one more per {@link Point#FLOOR_HEIGHT}. */
    default int floor() {
        return Math.floorDiv(minY(), Point.FLOOR_HEIGHT);
    }

    /** The middle tile, rounded toward the min corner. */
    default Point center() {
        return new Point((minX() + maxX()) / 2, (minY() + maxY()) / 2);
    }

    @Override
    default boolean contains(int x, int y) {
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY();
    }

    /** Steps, in king moves, between the closest tiles of the two areas; 0 when they overlap. */
    default int chebyshev(Area other) {
        int dx = Math.max(0, Math.max(other.minX() - maxX(), minX() - other.maxX()));
        int dy = Math.max(0, Math.max(other.minY() - maxY(), minY() - other.maxY()));
        return Math.max(dx, dy);
    }

    default Rect grow(int r) {
        return grow(r, r);
    }

    default Rect grow(int rx, int ry) {
        return new Rect(minX() - rx, minY() - ry, maxX() + rx, maxY() + ry);
    }
}
