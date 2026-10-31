package openrsc.nav.transports;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * The whole way into one room of the {@link ErnestBasementMaze} from any other, lever pulls and door
 * crossings included, played by a hand-written script. One transport per room, fired from the
 * anchor of every other room and landing on its own. It is always open: the levers are state the
 * script reads and changes, not a gate. Its cost is 30 per move of the way from lever A's room with
 * every lever up, as a fresh descent finds the basement.
 */
public final class ErnestBasementMazeTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.ERNEST_BASEMENT_MAZE);

    private static final int MOVE_COST = 30;

    private final String constant;
    private final ErnestBasementMaze maze;
    private final int target;
    private final List<Point> entries;
    private final int cost;
    private String name;

    private ErnestBasementMazeTransport(
            String constant, ErnestBasementMaze maze, int target, List<Point> entries, int cost) {
        this.constant = constant;
        this.maze = maze;
        this.target = target;
        this.entries = List.copyOf(entries);
        this.cost = cost;
    }

    /** One transport into each room of {@code env}'s basement, or none when the world has none. */
    public static List<Transport> forWorld(ServerData env, String id) {
        ErnestBasementMaze maze = ErnestBasementMaze.forWorld(env);
        if (maze == null) {
            return List.of();
        }
        int entryRoom = maze.levers().get(0).room();
        List<Transport> out = new ArrayList<>();
        for (ErnestBasementMaze.Room to : maze.rooms()) {
            List<Point> entries = new ArrayList<>();
            for (ErnestBasementMaze.Room from : maze.rooms()) {
                if (from.index() != to.index()) {
                    entries.add(from.anchor());
                }
            }
            int moves = maze.distance(entryRoom, 0, to.index());
            out.add(new ErnestBasementMazeTransport(id, maze, to.index(), entries, MOVE_COST * Math.max(1, moves)));
        }
        return out;
    }

    public ErnestBasementMaze maze() {
        return maze;
    }

    public int targetRoom() {
        return target;
    }

    @Override
    public Prereq prereq() {
        return Prereq.ALWAYS;
    }

    @Override
    public boolean isEntry(int x, int y) {
        for (Point e : entries) {
            if (e.x() == x && e.y() == y) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return entries;
    }

    @Override
    public Point destinationFrom(Point from) {
        return maze.rooms().get(target).anchor();
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    @Override
    public String arm() {
        Point a = maze.rooms().get(target).anchor();
        return "to_" + a.x() + "_" + a.y();
    }

    @Override
    public String costOverrideKey() {
        return constant;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }
}
