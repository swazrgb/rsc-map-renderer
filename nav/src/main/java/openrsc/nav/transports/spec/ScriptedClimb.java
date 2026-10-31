package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.LadderTransport;
import openrsc.nav.transports.ScriptedLadderTransport;
import openrsc.nav.transports.spec.Rows.Answers;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.When;

/**
 * A ladder, staircase or portal on one tile whose handler scripts it apart from the default climb: a
 * fixed landing, a gate, or a conversation first. Fired from the tiles its footprint is reached
 * from; a world that does not place the object there gets nothing.
 */
public record ScriptedClimb(
        int objectId, int x, int z, Point to, boolean column, boolean up, Prereq gate, String[][] answers, String note)
        implements TransportSpec {

    /** A climb's cost, in the planner's units of 10 per walked tile. */
    public static final int COST = 30;

    public record Placement(int objectId, int x, int z) {}

    public Placement placement() {
        return new Placement(objectId, x, z);
    }

    public static ScriptedClimb ladder(int objectId, int x, int z, Point to, Option... options) {
        allowOnly("a scripted climb", options, When.class, CanKillAt.class, Answers.class, Note.class);
        return new ScriptedClimb(
                objectId, x, z, to, false, false, Rows.gateOf(options), Rows.answersOf(options), Rows.noteOf(options));
    }

    /** {@link #ladder} at every placement of {@code objectId}, for a handler that matches the id alone. */
    public static EveryPlacement anywhere(int objectId, Point to, Option... options) {
        return EveryPlacement.everyScenery(objectId, p -> List.of(ladder(objectId, p.x(), p.z(), to, options)));
    }

    /**
     * A scripted climb that lands as the default climb does, in the player's own column one floor
     * {@code up} or down, once its gate or conversation is passed.
     */
    public static ScriptedClimb columnLadder(int objectId, int x, int z, boolean up, Option... options) {
        allowOnly("a scripted climb", options, When.class, CanKillAt.class, Answers.class, Note.class);
        return new ScriptedClimb(
                objectId,
                x,
                z,
                Point.of(x, LadderTransport.newY(z, up)),
                true,
                up,
                Rows.gateOf(options),
                Rows.answersOf(options),
                Rows.noteOf(options));
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        SceneryLocs.Loc placed = null;
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() == objectId && loc.pos().x() == x && loc.pos().y() == z) {
                placed = loc;
                break;
            }
        }
        if (placed == null) {
            return List.of();
        }
        ObjectDef def = env.objectDefs().get(objectId);
        int w = def == null ? 1 : def.width();
        int h = def == null ? 1 : def.height();
        List<Point> entries = Transport.entriesFor(env.collisionMap(), x, z, w, h, placed.direction());
        if (entries.isEmpty()) {
            return List.of();
        }
        ScriptedLadderTransport t =
                new ScriptedLadderTransport(objectId, x, z, to.x(), to.y(), gate, COST, entries).withNote(note);
        t.withPlacement(id);
        if (column) {
            t.coordPreserving(up);
        }
        if (answers.length > 0) {
            t.withDialog(answers);
        }
        return List.of(t);
    }
}
