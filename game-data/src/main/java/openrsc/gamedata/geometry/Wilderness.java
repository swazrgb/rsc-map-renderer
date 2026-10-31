package openrsc.gamedata.geometry;

/**
 * Where the wilderness is and its level, computed from the tile as the server does.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L372">Point.wildernessLevel</a>
 */
public final class Wilderness {

    private Wilderness() {}

    public static boolean contains(int x, int z) {
        if (x >= 336) {
            return false;
        }
        return wild(z) > 0;
    }

    /** The wilderness level: 1 at the southern edge, rising northward; 0 outside. */
    public static int level(int x, int z) {
        if (!contains(x, z)) {
            return 0;
        }
        return 1 + wild(z) / 6;
    }

    private static int wild(int z) {
        return 2203 - (z + (1776 - 944 * (z / 944)));
    }
}
