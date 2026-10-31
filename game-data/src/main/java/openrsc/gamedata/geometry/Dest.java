package openrsc.gamedata.geometry;

import java.util.List;

/**
 * Where a walk is asked to end: a tile, a rectangle (any tile in it), one of several areas, or a
 * {@link Targetable} to stand in reach of.
 */
public sealed interface Dest permits Area, Dest.AnyOf, Targetable {

    /** Any tile of any of the areas; the list is copied. */
    record AnyOf(List<Area> options) implements Dest {
        public AnyOf {
            options = List.copyOf(options);
        }

        @Override
        public boolean contains(int x, int y) {
            for (Area a : options) {
                if (a.contains(x, y)) {
                    return true;
                }
            }
            return false;
        }
    }

    static Dest anyOf(Area... options) {
        return new AnyOf(List.of(options));
    }

    /** Whether standing on the tile counts as having arrived. */
    boolean contains(int x, int y);

    default boolean contains(Point p) {
        return contains(p.x(), p.y());
    }
}
