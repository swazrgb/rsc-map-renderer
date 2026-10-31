package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.WallCrossings;
import openrsc.nav.transports.WallObjectTransport;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.When;

/**
 * Every placement of a wall object, fired from its flanks as {@link WallCrossing#wallTeleport} fires
 * one, landing on one fixed tile: a handler that matches the wall by its id alone. A placement a
 * {@link WallCrossing} row of {@link openrsc.nav.transports.WallCrossings} states is left to that row.
 */
public record AnyWallPlacement(int boundaryId, Point to, Prereq gate, int cost, String note) implements TransportSpec {

    public static AnyWallPlacement anyWall(int boundaryId, Point to, int cost, Option... options) {
        allowOnly("an any-placement wall", options, When.class, CanKillAt.class, Note.class);
        return new AnyWallPlacement(boundaryId, to, Rows.gateOf(options), cost, Rows.noteOf(options));
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<Transport> out = new ArrayList<>();
        for (BoundaryLocs.Loc loc : env.boundaryLocs()) {
            int x = loc.pos().x();
            int z = loc.pos().y();
            if (loc.id() != boundaryId || WallCrossings.statesWall(boundaryId, x, z)) {
                continue;
            }
            out.add(new WallObjectTransport(
                            boundaryId,
                            x,
                            z,
                            WallCrossing.flanks(x, z, loc.direction()),
                            to.x(),
                            to.y(),
                            gate,
                            cost,
                            note)
                    .withPlacement(id)
                    .withWalkableEntries(env.collisionMap()));
        }
        return out;
    }
}
