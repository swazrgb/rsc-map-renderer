package openrsc.nav.walk;

import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.Planner;
import openrsc.nav.Route;

/**
 * The ground a walk is searched over: which steps are legal, which tiles a body stands on, and what
 * entering a tile costs beyond the step. {@link #ofMap} is the bare static map; a host with a live
 * view of the tick (open doors, the NPCs in view) supplies its own.
 */
public interface StepView {

    /**
     * Whether one step from {@code (fx, fy)} to the adjacent {@code (tx, ty)} is legal: the static
     * walls as {@link CollisionMap#canStep} reads them, plus whatever the view knows that the static
     * map does not.
     */
    boolean canStep(int fx, int fy, int tx, int ty);

    /** Whether a body stands on {@code (x, y)} this tick. */
    boolean occupied(int x, int y);

    /** What entering {@code (x, y)} costs on top of the step itself, in deci-ticks; 0 for nothing. */
    int extra(int x, int y);

    /**
     * Whether {@code hop}'s obstacle is already gone in this view, so the crossing is plain ground and
     * walked like any other. The static map never has one open.
     */
    default boolean crossingOpen(Route.Hop hop) {
        return false;
    }

    /**
     * Whether {@code place}, a hop or a bank stop, is handed to its own driver from {@code pos} rather
     * than walked to. By default once {@code pos} stands on the place's tile.
     */
    default boolean drives(Route.Step place, Point pos) {
        Rect at = RouteFollower.stepTarget(place);
        return at != null && at.contains(pos.x(), pos.y());
    }

    /**
     * {@code charge} with nothing charged within {@code radius} of {@code at} (Pythagorean): inside
     * it a walker sees the NPCs standing there and berths them, beyond it the field is all it has.
     * {@code radius} is the server's NPC view radius.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L182">GameStateUpdater.updateNpcs</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369">Mob.withinRange</a>
     */
    static Planner.DangerCharge beyond(Planner.DangerCharge charge, Point at, int radius) {
        return (x, y) -> Point.pythagoras(at.x(), at.y(), x, y) <= radius ? 0 : charge.at(x, y);
    }

    /**
     * The static map with {@code berth}'s blocking NPCs as bodies, and its rings charged when
     * {@code berthOn}, on top of {@code field}.
     */
    static StepView amongNpcs(CollisionMap map, Planner.DangerCharge field, Berth berth, boolean berthOn) {
        return new StepView() {
            @Override
            public boolean canStep(int fx, int fy, int tx, int ty) {
                return map.canStep(fx, fy, tx, ty, Overlays.NONE);
            }

            @Override
            public boolean occupied(int x, int y) {
                return berth.blocks(x, y);
            }

            @Override
            public int extra(int x, int y) {
                return (berthOn ? berth.cost(x, y) : 0) + field.at(x, y);
            }
        };
    }

    /** The static map, with no bodies, charged {@code danger}. */
    static StepView ofMap(CollisionMap map, Planner.DangerCharge danger) {
        return new StepView() {
            @Override
            public boolean canStep(int fx, int fy, int tx, int ty) {
                return map.canStep(fx, fy, tx, ty, Overlays.NONE);
            }

            @Override
            public boolean occupied(int x, int y) {
                return false;
            }

            @Override
            public int extra(int x, int y) {
                return danger.at(x, y);
            }
        };
    }
}
