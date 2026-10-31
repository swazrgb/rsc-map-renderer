package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ScriptedLadderTransport;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.When;

/**
 * Every placement of a scenery object, landing on one fixed tile: a handler that matches the object
 * by its id alone. Each entry tile is its own transport, engaging the placement it was derived from;
 * a tile beside two placements serves the first.
 */
public record AnyPlacement(int objectId, Point to, Prereq gate, int cost, String note, Set<Point> excepted)
        implements TransportSpec {

    public static AnyPlacement anyPlacement(int objectId, Point to, int cost, Option... options) {
        allowOnly("an any-placement crossing", options, When.class, CanKillAt.class, Note.class);
        return new AnyPlacement(objectId, to, Rows.gateOf(options), cost, Rows.noteOf(options), Set.of());
    }

    /** As {@link EveryPlacement#except}: these placements left out, each one the world must place. */
    public AnyPlacement except(Point... tiles) {
        Set<Point> out = new HashSet<>(excepted);
        out.addAll(List.of(tiles));
        return new AnyPlacement(objectId, to, gate, cost, note, Set.copyOf(out));
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        Set<Point> seen = new HashSet<>();
        Set<Point> placed = new HashSet<>();
        List<Transport> out = new ArrayList<>();
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() != objectId) {
                continue;
            }
            Point at = Point.of(loc.pos().x(), loc.pos().y());
            placed.add(at);
            if (excepted.contains(at)) {
                continue;
            }
            ObjectDef def = env.objectDefs().get(objectId);
            List<Point> entries = def == null
                    ? Transport.enumerateEntries(
                            env.collisionMap(), loc.pos().x(), loc.pos().y())
                    : Transport.entriesFor(
                            env.collisionMap(),
                            loc.pos().x(),
                            loc.pos().y(),
                            def.width(),
                            def.height(),
                            loc.direction());
            for (Point e : entries) {
                if (!seen.add(e)) {
                    continue;
                }
                ScriptedLadderTransport t = new ScriptedLadderTransport(
                                objectId, loc.pos().x(), loc.pos().y(), to.x(), to.y(), gate, cost, List.of(e))
                        .withNote(note);
                t.withPlacement(id);
                out.add(t);
            }
        }
        if (!placed.isEmpty() && !placed.containsAll(excepted)) {
            throw new IllegalStateException(id + ": excepts a tile that places no " + objectId + ": " + excepted);
        }
        return out;
    }
}
