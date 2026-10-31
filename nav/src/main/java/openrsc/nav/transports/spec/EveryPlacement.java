package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;

/**
 * The crossings a handler that matches an object by its id alone makes of every placement of that id
 * the world loads: {@code row} gives them as a function of the placement, with any landing the
 * handler computes from the object or the player written against it. Its transports are named by
 * placement, and each must engage the placement it was made for, or expansion throws.
 *
 * <p>{@link #facing} and {@link #writtenFor} state the facings or placements a row was written for;
 * any other placement then throws, rather than becoming a crossing nobody checked.
 */
public record EveryPlacement(
        boolean boundary, int objectId, Row row, Set<Point> excepted, Set<Integer> facings, Set<Point> writtenFor)
        implements TransportSpec {

    /** One placement: the object, its tile and its direction. */
    public record Placed(int objectId, int x, int z, int dir) {

        public Point at() {
            return Point.of(x, z);
        }

        public Point plus(int dx, int dz) {
            return Point.of(x + dx, z + dz);
        }

        /**
         * Where the server's gate handler puts a player on {@code from} who opens this scenery gate.
         * Throws for a facing it moves nobody through.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1819">Functions.doGate</a>
         */
        public Point doGateLanding(Point from) {
            return switch (dir) {
                case 0 -> from.x() >= x ? Point.of(x - 1, z) : at();
                case 2 -> from.y() <= z ? Point.of(x, z + 1) : at();
                case 4 -> from.x() > x ? at() : Point.of(x + 1, z);
                case 6 -> from.y() >= z ? Point.of(x, z - 1) : at();
                default ->
                    throw new IllegalStateException(
                            "doGate moves nobody through " + objectId + " @" + at() + " facing " + dir);
            };
        }
    }

    /** The crossings of one placement; any number, each engaging that placement. */
    @FunctionalInterface
    public interface Row {
        List<? extends TransportSpec> at(Placed p);
    }

    public static EveryPlacement everyScenery(int objectId, Row row) {
        return new EveryPlacement(false, objectId, row, Set.of(), Set.of(), Set.of());
    }

    public static EveryPlacement everyBoundary(int boundaryId, Row row) {
        return new EveryPlacement(true, boundaryId, row, Set.of(), Set.of(), Set.of());
    }

    /**
     * These placements left out: one the handler special-cases by its coordinates, declared by a
     * constant of its own, or one the server deletes at runtime by spawning another object onto its
     * tile. Expansion throws when an excepted tile places no such object in a world that places it
     * elsewhere.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L494">World.registerGameObject</a>
     */
    public EveryPlacement except(Point... tiles) {
        Set<Point> out = new HashSet<>(excepted);
        out.addAll(List.of(tiles));
        return new EveryPlacement(boundary, objectId, row, Set.copyOf(out), facings, writtenFor);
    }

    /** Only placements facing one of {@code dirs}; any other throws at expansion. */
    public EveryPlacement facing(Integer... dirs) {
        return new EveryPlacement(boundary, objectId, row, excepted, Set.of(dirs), writtenFor);
    }

    /** Only the placements on {@code tiles}; any other throws at expansion. */
    public EveryPlacement writtenFor(Point... tiles) {
        return new EveryPlacement(boundary, objectId, row, excepted, facings, Set.of(tiles));
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<Placed> placed = new ArrayList<>();
        if (boundary) {
            for (BoundaryLocs.Loc loc : env.boundaryLocs()) {
                if (loc.id() == objectId) {
                    placed.add(new Placed(objectId, loc.pos().x(), loc.pos().y(), loc.direction()));
                }
            }
        } else {
            for (SceneryLocs.Loc loc : env.sceneryLocs()) {
                if (loc.id() == objectId) {
                    placed.add(new Placed(objectId, loc.pos().x(), loc.pos().y(), loc.direction()));
                }
            }
        }
        if (!placed.isEmpty()) {
            Set<Point> tiles = new HashSet<>();
            for (Placed p : placed) {
                tiles.add(p.at());
            }
            for (Point e : excepted) {
                if (!tiles.contains(e)) {
                    throw new IllegalStateException(id + ": excepts " + e + ", which places no " + objectId);
                }
            }
        }
        List<Transport> out = new ArrayList<>();
        for (Placed p : placed) {
            expandAt(env, id, p, out);
        }
        return out;
    }

    private void expandAt(ServerData env, String id, Placed p, List<Transport> out) {
        if (excepted.contains(p.at())) {
            return;
        }
        if (!facings.isEmpty() && !facings.contains(p.dir())) {
            throw new IllegalStateException(id + ": " + objectId + " @" + p.at() + " faces " + p.dir()
                    + "; its row was written for " + facings);
        }
        if (!writtenFor.isEmpty() && !writtenFor.contains(p.at())) {
            throw new IllegalStateException(
                    id + ": " + objectId + " @" + p.at() + " is a placement its row was not written for");
        }
        for (TransportSpec spec : row.at(p)) {
            for (Transport t : spec.expand(env, id)) {
                Point engaged = engaged(t);
                if (!p.at().equals(engaged)) {
                    throw new IllegalStateException(
                            id + ": the row for " + objectId + " @" + p.at() + " engages " + engaged);
                }
                int engagedId = engagedId(t);
                if (engagedId >= 0 && engagedId != objectId) {
                    throw new IllegalStateException(
                            id + ": the row for " + objectId + " @" + p.at() + " engages object " + engagedId);
                }
                out.add(t);
            }
        }
    }

    private static Point engaged(Transport t) {
        EngageSpec.Target.Boundary wall = t.boundary();
        return wall != null ? Point.of(wall.x(), wall.y()) : t.sceneryAnchor();
    }

    private static int engagedId(Transport t) {
        EngageSpec.Target.Boundary wall = t.boundary();
        if (wall != null) {
            return wall.boundaryId();
        }
        if (t instanceof DeclarativeTransport d && d.engageSpec().target() instanceof EngageSpec.Target.Scenery s) {
            return s.objectId();
        }
        return -1;
    }
}
