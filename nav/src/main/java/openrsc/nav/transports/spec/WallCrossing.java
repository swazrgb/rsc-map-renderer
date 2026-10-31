package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.WallObjectTransport;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.When;

/**
 * Operate one wall object from stated tiles and be moved to a fixed tile: a portal, a magic door, a
 * railing squeezed through. When every stated tile is blocked, as a diagonal wall's own tile is, the
 * wall's walkable neighbours are used instead.
 */
public record WallCrossing(int boundaryId, int x, int z, List<Point> from, Point to, Prereq gate, int cost, String note)
        implements TransportSpec {

    public static WallCrossing wall(int boundaryId, int x, int z, Point from, Point to, int cost, Option... options) {
        return wall(boundaryId, x, z, List.of(from), to, cost, options);
    }

    public static WallCrossing wall(
            int boundaryId, int x, int z, List<Point> from, Point to, int cost, Option... options) {
        allowOnly("a wall teleport", options, When.class, CanKillAt.class, Note.class);
        return new WallCrossing(
                boundaryId, x, z, List.copyOf(from), to, Rows.gateOf(options), cost, Rows.noteOf(options));
    }

    /**
     * An ungated wall fired from either flank: for {@code dir} 0 or 1 the tiles either side of the
     * edge, for a diagonal its own tile.
     */
    public static WallCrossing wallTeleport(int boundaryId, int x, int z, int dir, Point to) {
        return new WallCrossing(boundaryId, x, z, flanks(x, z, dir), to, Prereq.ALWAYS, TELEPORT_COST, null);
    }

    static final int TELEPORT_COST = 80;

    static List<Point> flanks(int x, int z, int dir) {
        return switch (dir) {
            case 0 -> List.of(Point.of(x, z - 1), Point.of(x, z));
            case 1 -> List.of(Point.of(x - 1, z), Point.of(x, z));
            default -> List.of(Point.of(x, z));
        };
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        return List.of(new WallObjectTransport(boundaryId, x, z, from, to.x(), to.y(), gate, cost, note)
                .withPlacement(id)
                .withWalkableEntries(env.collisionMap()));
    }
}
