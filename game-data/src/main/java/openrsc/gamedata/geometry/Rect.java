package openrsc.gamedata.geometry;

/** A rectangle of tiles with both corners inside. Corners given in either order are swapped into place. */
public record Rect(int minX, int minY, int maxX, int maxY) implements Area {

    public Rect {
        if (minX > maxX) {
            int t = minX;
            minX = maxX;
            maxX = t;
        }
        if (minY > maxY) {
            int t = minY;
            minY = maxY;
            maxY = t;
        }
    }

    public static Rect of(int minX, int minY, int maxX, int maxY) {
        return new Rect(minX, minY, maxX, maxY);
    }

    public static Rect of(Point a, Point b) {
        return new Rect(a.x(), a.y(), b.x(), b.y());
    }

    /**
     * The tiles a scenery of {@code width × height} covers from its anchor tile toward {@code +x} and
     * {@code +y}. Width and height swap unless {@code dir} is 0 or 4, as the server places it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L511">World.registerGameObject</a>
     */
    public static Rect footprint(int anchorX, int anchorY, int width, int height, int dir) {
        int w = width, h = height;
        if (dir != 0 && dir != 4) {
            w = height;
            h = width;
        }
        return new Rect(anchorX, anchorY, anchorX + w - 1, anchorY + h - 1);
    }

    @Override
    public String toString() {
        return "Rect[(" + minX + ", " + minY + ")..(" + maxX + ", " + maxY + ")]";
    }
}
