package openrsc.nav.transports;

import java.util.List;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * Operate a wall object, or use an item on it, from one of its entry tiles and be moved to a fixed
 * tile. Every refinement returns a copy; the instance itself does not change.
 */
public final class WallObjectTransport implements Transport, DeclarativeTransport {

    private String name;
    private final int boundaryId;
    private final int wallX, wallY;
    private final List<Point> entryTiles;
    private final int destX, destY;
    private final Prereq prereq;

    private final int cost;
    private final String note;
    private final String placementId;

    private final int wallDir;

    private final int useItemId;
    private final boolean perEntry;

    public WallObjectTransport(
            int boundaryId,
            int wallX,
            int wallY,
            List<Point> entryTiles,
            int destX,
            int destY,
            Prereq prereq,
            int cost,
            String note) {
        this(null, boundaryId, wallX, wallY, -1, entryTiles, destX, destY, prereq, cost, note, null, -1, false);
    }

    private WallObjectTransport(
            String name,
            int boundaryId,
            int wallX,
            int wallY,
            int wallDir,
            List<Point> entryTiles,
            int destX,
            int destY,
            Prereq prereq,
            int cost,
            String note,
            String placementId,
            int useItemId,
            boolean perEntry) {
        this.name = name;
        this.boundaryId = boundaryId;
        this.wallX = wallX;
        this.wallY = wallY;
        this.wallDir = wallDir;
        this.entryTiles = List.copyOf(entryTiles);
        this.destX = destX;
        this.destY = destY;
        this.prereq = prereq;
        this.cost = cost;
        this.note = note;
        this.placementId = placementId;
        this.useItemId = useItemId;
        this.perEntry = perEntry;
    }

    public WallObjectTransport withPlacement(String id) {
        return new WallObjectTransport(
                name,
                boundaryId,
                wallX,
                wallY,
                wallDir,
                entryTiles,
                destX,
                destY,
                prereq,
                cost,
                note,
                id,
                useItemId,
                perEntry);
    }

    /** This crossing fired by using {@code itemId} on the wall; its arm is named {@code douse_x_y}. */
    public WallObjectTransport withUseItem(int itemId) {
        return new WallObjectTransport(
                name,
                boundaryId,
                wallX,
                wallY,
                wallDir,
                entryTiles,
                destX,
                destY,
                prereq,
                cost,
                note,
                placementId,
                itemId,
                perEntry);
    }

    /**
     * This crossing marked as landing by the tile it is fired from, as a wall the server moves the
     * player across does, so it must be fired from its planned entry.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1632">Functions.doWallMovePlayer</a>
     */
    public WallObjectTransport landingByEntry() {
        return new WallObjectTransport(
                name,
                boundaryId,
                wallX,
                wallY,
                wallDir,
                entryTiles,
                destX,
                destY,
                prereq,
                cost,
                note,
                placementId,
                useItemId,
                true);
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }

    /**
     * This crossing, or, when no entry can be stood on, a copy fired from the walkable tiles beside
     * the wall instead (a diagonal wall's own tile is blocked). Unchanged when none can be derived.
     */
    public WallObjectTransport withWalkableEntries(CollisionMap map) {
        for (Point e : entryTiles) {
            if (map.inBounds(e.x(), e.y()) && !map.fullyBlocked(e.x(), e.y()) && map.componentId(e.x(), e.y()) != 0) {
                return this;
            }
        }
        List<Point> derived = Transport.enumerateEntries(map, wallX, wallY);
        if (derived.isEmpty()) {
            return this;
        }
        return new WallObjectTransport(
                name,
                boundaryId,
                wallX,
                wallY,
                wallDir,
                derived,
                destX,
                destY,
                prereq,
                cost,
                note,
                placementId,
                useItemId,
                perEntry);
    }

    /**
     * This crossing with its wall's direction read off {@code placements}, from the one placement of
     * its id on its tile. The server finds a wall by tile and direction; with none or several such
     * placements the direction stays unknown, {@code -1}, matching any.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectWallAction.java#L51">GameObjectWallAction.process</a>
     */
    public WallObjectTransport withEdgeFrom(List<BoundaryLocs.Loc> placements) {
        if (wallDir >= 0) {
            return this;
        }
        int found = -1;
        for (BoundaryLocs.Loc loc : placements) {
            if (loc.id() == boundaryId && loc.pos().x() == wallX && loc.pos().y() == wallY) {
                if (found >= 0) {
                    return this;
                }
                found = loc.direction();
            }
        }
        return found < 0
                ? this
                : new WallObjectTransport(
                        name,
                        boundaryId,
                        wallX,
                        wallY,
                        found,
                        entryTiles,
                        destX,
                        destY,
                        prereq,
                        cost,
                        note,
                        placementId,
                        useItemId,
                        perEntry);
    }

    @Override
    public boolean landingDependsOnEntry() {
        return perEntry;
    }

    @Override
    public String arm() {
        if (useItemId < 0) {
            return null;
        }
        Point e = entryTiles.get(0);
        return "douse_" + e.x() + "_" + e.y();
    }

    @Override
    public boolean isEntry(int x, int y) {
        for (Point t : entryTiles) {
            if (t.x() == x && t.y() == y) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return entryTiles;
    }

    @Override
    public EngageSpec.Target.Boundary boundary() {
        return new EngageSpec.Target.Boundary(boundaryId, wallX, wallY, wallDir);
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public Prereq prereq() {
        return prereq;
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public EngageSpec engageSpec() {
        return useItemId >= 0
                ? EngageSpec.of(EngageSpec.Action.useItem(useItemId), boundary())
                : EngageSpec.of(EngageSpec.Action.INTERACT_1, boundary());
    }
}
