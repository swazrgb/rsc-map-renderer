package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ScriptedLadderTransport;
import openrsc.nav.transports.spec.Rows.Answers;
import openrsc.nav.transports.spec.Rows.Approach;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.Pays;
import openrsc.nav.transports.spec.Rows.SecondCommand;
import openrsc.nav.transports.spec.Rows.Spawned;
import openrsc.nav.transports.spec.Rows.UsesItem;
import openrsc.nav.transports.spec.Rows.When;

/**
 * Operate the object on {@code (x, y)} and be moved to a fixed tile: a quest or agility obstacle. The
 * entries are derived from the object's definition and the collision map: anywhere in the boundary
 * of an object walked onto, else around its footprint. An entry is kept only when the crossing
 * serves it, one step from the landing or in a walking component the landing is not in, and never on
 * the landing.
 *
 * <p>{@code prereq} is {@code null} for no gate; {@code useItemId} is the item used on the object, or
 * {@code -1} for a click, and {@code consumeCount} how many of it the use spends; {@code fare} is a
 * toll the handler takes instead, or {@code null}; the {@code entry} bounds keep this arm to one side
 * of the object; {@code spawnDirection} is the direction of an object the handler spawns, or
 * {@code -1} for one the landscape places.
 */
public record SceneryObstacle(
        int id,
        int x,
        int y,
        int destX,
        int destY,
        int cost,
        Prereq prereq,
        String[][] menuAnswers,
        int useItemId,
        int consumeCount,
        Pays fare,
        boolean command2,
        int entryXMin,
        int entryXMax,
        int entryYMin,
        int entryYMax,
        int spawnDirection,
        String note)
        implements TransportSpec {

    /**
     * Operate the object on {@code (x, y)}; throws when a used item that is spent and a fare are both
     * given, since the crossing spends one of them.
     */
    public static SceneryObstacle obstacle(int id, int x, int y, Point to, int cost, Option... options) {
        allowOnly(
                "a quest obstacle",
                options,
                When.class,
                Pays.class,
                UsesItem.class,
                Answers.class,
                SecondCommand.class,
                Approach.class,
                Spawned.class,
                CanKillAt.class,
                Note.class);
        When when = Rows.find(When.class, options);
        CanKillAt kills = Rows.find(CanKillAt.class, options);
        UsesItem item = Rows.find(UsesItem.class, options);
        Pays fare = Rows.paysOf(options);
        if (fare != null && item != null && item.spend() > 0) {
            throw new IllegalArgumentException("a quest obstacle spends either its used item or a fare, not both");
        }
        Prereq gate = null;
        if (item != null) {
            gate = Prereq.fact(Fact.carriedAtLeast(item.itemId(), Math.max(1, item.spend())));
        }
        if (when != null) {
            gate = gate == null ? when.gate() : Prereq.all(gate, when.gate());
        }
        if (fare != null) {
            Prereq paid = Prereq.fact(Fact.carriedAtLeast(fare.itemId(), fare.count()));
            gate = gate == null ? paid : Prereq.all(gate, paid);
        }
        if (kills != null) {
            gate = gate == null ? Rows.survives(kills.hits()) : Prereq.all(gate, Rows.survives(kills.hits()));
        }
        Approach side = Rows.approachOf(options);
        Spawned spawned = Rows.find(Spawned.class, options);
        return new SceneryObstacle(
                id,
                x,
                y,
                to.x(),
                to.y(),
                cost,
                gate,
                Rows.answersOf(options),
                item == null ? -1 : item.itemId(),
                item == null ? 0 : item.spend(),
                fare,
                Rows.has(SecondCommand.class, options),
                side.xMin(),
                side.xMax(),
                side.yMin(),
                side.yMax(),
                spawned == null ? -1 : spawned.direction(),
                Rows.noteOf(options));
    }

    /** {@link #obstacle} at every placement of {@code id}; its options exclude the approach bounds. */
    public static AnyObstacle obstacleAnywhere(int id, Point to, int cost, Option... options) {
        allowOnly(
                "an any-placement quest obstacle",
                options,
                When.class,
                Pays.class,
                UsesItem.class,
                Answers.class,
                SecondCommand.class,
                CanKillAt.class,
                Note.class);
        return new AnyObstacle(obstacle(id, 0, 0, to, cost, options));
    }

    @Override
    public List<Transport> expand(ServerData env, String constant) {
        return expandAt(env, constant, x, y);
    }

    List<Transport> expandAt(ServerData env, String constant, int x, int y) {
        CollisionMap map = env.collisionMap();
        ObjectDefs defs = env.objectDefs();
        List<SceneryLocs.Loc> sceneries = env.sceneryLocs();
        int destComp = map.componentId(destX(), destY());
        ObjectDef def = defs.get(id());
        List<Point> candidates;
        if (def != null && (def.typ() == 2 || def.typ() == 3)) {
            int direction = direction(sceneries, x, y);
            if (direction < 0) {
                return List.of();
            }
            int[] rect = Transport.walkOnBoundary(x, y, direction, def.width(), def.height());
            candidates = Transport.enumerateWalkOnEntries(map, rect);
        } else if (def != null && def.width() * def.height() > 1) {
            int direction = direction(sceneries, x, y);
            if (direction < 0) {
                return List.of();
            }
            candidates = Transport.entriesFor(map, x, y, def.width(), def.height(), direction);
        } else {
            candidates = Transport.enumerateEntries(map, x, y);
        }
        List<Point> entries = new ArrayList<>();
        for (Point e : candidates) {
            if (e.x() < entryXMin() || e.x() > entryXMax() || e.y() < entryYMin() || e.y() > entryYMax()) {
                continue;
            }
            if (e.x() == destX() && e.y() == destY()) {
                continue;
            }
            boolean oneStep = map.canStep(e.x(), e.y(), destX(), destY(), Overlays.NONE);
            boolean bridges;
            if (map.fullyBlocked(e.x(), e.y())) {
                // A blocked tile has no component of its own: it bridges only when no step off it
                // already walks to the landing.
                bridges = true;
                for (int[] c : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int nx = e.x() + c[0], ny = e.y() + c[1];
                    if (map.inBounds(nx, ny)
                            && !map.fullyBlocked(nx, ny)
                            && map.canStep(e.x(), e.y(), nx, ny, Overlays.NONE)
                            && map.componentId(nx, ny) == destComp) {
                        bridges = false;
                        break;
                    }
                }
            } else {
                bridges = map.componentId(e.x(), e.y()) != destComp;
            }
            if (oneStep || bridges) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        Prereq p = prereq();
        ScriptedLadderTransport t = new ScriptedLadderTransport(
                id(), x, y, destX(), destY(), p == null ? Prereq.ALWAYS : p, cost(), entries);
        t.withNote(note());
        t.withPlacement(constant);
        if (menuAnswers().length > 0) {
            t.withDialog(menuAnswers());
        }
        if (command2()) {
            t.usingCommand2();
        }
        if (useItemId() >= 0) {
            t.usingItem(useItemId());
            if (consumeCount() > 0) {
                t.consuming(useItemId(), consumeCount());
            }
        }
        if (fare() != null) {
            t.consuming(fare().itemId(), fare().count());
        }
        return List.of(t);
    }

    private int direction(List<SceneryLocs.Loc> sceneries, int x, int y) {
        int direction = spawnDirection();
        if (direction >= 0) {
            return direction;
        }
        return sceneries.stream()
                .filter(l -> l.id() == id() && l.pos().x() == x && l.pos().y() == y)
                .findFirst()
                .map(SceneryLocs.Loc::direction)
                .orElse(-1);
    }
}
