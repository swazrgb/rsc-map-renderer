package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ObjectMenuTransport;
import openrsc.nav.transports.ScriptedLadderTransport;

/**
 * Operate one placed object and be moved to a fixed tile, fired from any walkable tile beside it.
 * The menu forms answer the dialogue the click opens, one keyword group per menu. A world that does
 * not place the object, or walls off every tile beside it, gets nothing.
 */
public record ObjectTeleport(
        int objectId,
        int x,
        int z,
        int destX,
        int destZ,
        int entryX,
        int entryZ,
        String[][] menu,
        Prereq gate,
        int cost,
        String note)
        implements TransportSpec {

    /** The {@code entryX} of a menu object fired from the first walkable tile beside it. */
    public static final int DERIVE_ENTRY = Integer.MIN_VALUE;

    public static ObjectTeleport object(int objectId, int x, int z, int destX, int destZ, int cost) {
        return new ObjectTeleport(
                objectId, x, z, destX, destZ, DERIVE_ENTRY, DERIVE_ENTRY, null, Prereq.ALWAYS, cost, null);
    }

    public static ObjectTeleport menuObject(
            int objectId, int x, int z, int destX, int destZ, int cost, String note, String[]... menu) {
        return new ObjectTeleport(
                objectId, x, z, destX, destZ, DERIVE_ENTRY, DERIVE_ENTRY, menu, Prereq.ALWAYS, cost, note);
    }

    /** A menu object fired from {@code (entryX, entryZ)} only, for one the server serves from one side. */
    public static ObjectTeleport menuObjectFrom(
            int objectId,
            int x,
            int z,
            int entryX,
            int entryZ,
            int destX,
            int destZ,
            Prereq gate,
            int cost,
            String note,
            String[]... menu) {
        return new ObjectTeleport(objectId, x, z, destX, destZ, entryX, entryZ, menu, gate, cost, note);
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<Point> entries = Transport.entriesFor(env.collisionMap(), x, z, 1, 1, 0);
        if (entries.isEmpty()) {
            return List.of();
        }
        if (menu == null) {
            return List.of(new ScriptedLadderTransport(objectId, x, z, destX, destZ, gate, cost, entries)
                    .withPlacement(id)
                    .withNote(note));
        }
        int ex = entryX;
        int ez = entryZ;
        if (ex == DERIVE_ENTRY) {
            Point e = entries.get(0);
            ex = e.x();
            ez = e.y();
        }
        return List.of(new ObjectMenuTransport(objectId, x, z, ex, ez, destX, destZ, menu, gate, cost, note)
                .withPlacement(id));
    }
}
