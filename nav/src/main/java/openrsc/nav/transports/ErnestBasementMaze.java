package openrsc.nav.transports;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;

/**
 * The Draynor Manor basement of Ernest the Chicken as a puzzle: its rooms (the walking components the
 * lever doors join), its lever doors and its six levers, and a search for the next move from any room
 * under any lever positions.
 *
 * <p>A lever pull toggles that lever between up and down for this player alone, and the ladder out
 * resets all six, which opens no door, as all up does. Each door opens only on its combinations
 * ({@link #COMBINATIONS}), and an opened door moves the player to its far side. The rooms are found by
 * flooding from door 30 at (226,3378), the one door the server matches by its tile, so copies of these
 * door ids elsewhere are not part of the maze.
 *
 * <p>Lever masks: bit {@code i} is lever {@code i} (A is 0), set when the lever is down.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ErnestTheChicken.java#L242">ErnestTheChicken.doLever</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ErnestTheChicken.java#L155">ErnestTheChicken.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ErnestTheChicken.java#L534">ErnestTheChicken.onOpBound</a>
 */
public final class ErnestBasementMaze {

    public static final int LEVERS = 6;

    public static final int ALL_LEVERS = (1 << LEVERS) - 1;

    private static final int[] LEVER_IDS = {124, 125, 126, 127, 128, 129};

    /**
     * What an opened door stands as for 3 seconds: an open frame the player cannot click.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1692">Functions.doDoor</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1708">Functions.doDoor</a>
     */
    public static final int OPEN_DOORFRAME = 11;

    private static final int A = 1, B = 1 << 1, C = 1 << 2, D = 1 << 3, E = 1 << 4, F = 1 << 5;

    private static final int SEED_DOOR = 30;

    private static final Point SEED_TILE = Point.of(226, 3378);

    /** One way a door opens: every lever in {@code down} down and every lever in {@code up} up. */
    public record Combination(int down, int up) {
        boolean opens(int levers) {
            return (levers & down) == down && (levers & up) == 0;
        }

        int levers() {
            return down | up;
        }
    }

    /**
     * Each door's combinations; any one opens it. Door 25's two server branches differ only in lever C,
     * so one combination that leaves C out stands for both.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ErnestTheChicken.java#L578">ErnestTheChicken.onOpBound</a>
     */
    static final Map<Integer, List<Combination>> COMBINATIONS = Map.of(
            25, List.of(new Combination(D | E | F, A | B)),
            26, List.of(new Combination(F | D, B)),
            27, List.of(new Combination(A | B | D, 0)),
            28, List.of(new Combination(D, 0)),
            29, List.of(new Combination(C | D | F, A | B | E)),
            30, List.of(new Combination(F, E)),
            31, List.of(new Combination(D, B | F)),
            32, List.of(new Combination(A | B, C | D | E | F)),
            33, List.of(new Combination(D, C), new Combination(C | D | F, A | B | E)));

    /** One room: its walking component, and {@code anchor}, a walkable tile of it beside a door. */
    public record Room(int index, int component, Point anchor) {}

    /**
     * A lever door on {@code wall}, facing {@code dir} 0 or 1, between its low flank ({@code y - 1} or
     * {@code x - 1}) and its high flank, the wall tile itself.
     */
    public record Door(int id, Point wall, int dir, int lowRoom, Point lowFlank, int highRoom, Point highFlank) {

        public boolean opens(int levers) {
            for (Combination c : COMBINATIONS.get(id)) {
                if (c.opens(levers)) {
                    return true;
                }
            }
            return false;
        }

        /** The mask of every lever one of this door's combinations reads. */
        public int reads() {
            int m = 0;
            for (Combination c : COMBINATIONS.get(id)) {
                m |= c.levers();
            }
            return m;
        }

        public Point flankIn(int room) {
            return room == lowRoom ? lowFlank : highFlank;
        }

        public int across(int room) {
            return room == lowRoom ? highRoom : lowRoom;
        }
    }

    public record Lever(int index, int sceneryId, Point at, int room) {

        public char letter() {
            return leverName(index);
        }
    }

    public static char leverName(int i) {
        return (char) ('A' + i);
    }

    /** The next step through the maze: a lever pull, or a door crossing. */
    public sealed interface Move {}

    public record Pull(Lever lever) implements Move {}

    public record Cross(Door door, int from, int to) implements Move {
        public Point near() {
            return door.flankIn(from);
        }
    }

    private final List<Room> rooms;
    private final List<Door> doors;
    private final List<Lever> levers;
    private final Map<Integer, Integer> roomByComponent;
    private final CollisionMap map;

    private ErnestBasementMaze(CollisionMap map, List<Room> rooms, List<Door> doors, List<Lever> levers) {
        this.map = map;
        this.rooms = List.copyOf(rooms);
        this.doors = List.copyOf(doors);
        this.levers = List.copyOf(levers);
        Map<Integer, Integer> byComp = new LinkedHashMap<>();
        for (Room r : rooms) {
            byComp.put(r.component(), r.index());
        }
        this.roomByComponent = Map.copyOf(byComp);
    }

    /**
     * The basement of {@code env}'s world, or {@code null} when it has none. Throws when a lever is not
     * placed in exactly one of its rooms.
     */
    public static @Nullable ErnestBasementMaze forWorld(ServerData env) {
        CollisionMap map = env.collisionMap();
        List<BoundaryLocs.Loc> placed = new ArrayList<>();
        for (BoundaryLocs.Loc b : env.boundaryLocs()) {
            boolean byId = COMBINATIONS.containsKey(b.id()) && b.id() != SEED_DOOR;
            boolean seed = b.id() == SEED_DOOR
                    && b.pos().x() == SEED_TILE.x()
                    && b.pos().y() == SEED_TILE.y();
            if ((byId || seed) && (b.direction() == 0 || b.direction() == 1)) {
                placed.add(b);
            }
        }
        List<Integer> comps = new ArrayList<>();
        boolean grew = true;
        boolean seeded = false;
        while (grew) {
            grew = false;
            for (BoundaryLocs.Loc b : placed) {
                Point high = Point.of(b.pos().x(), b.pos().y());
                Point low = low(b);
                int hc = map.componentId(high.x(), high.y());
                int lc = map.componentId(low.x(), low.y());
                if (hc == 0 || lc == 0 || hc == lc) {
                    continue;
                }
                boolean isSeed = b.id() == SEED_DOOR;
                if ((isSeed && !seeded) || comps.contains(hc) || comps.contains(lc)) {
                    seeded |= isSeed;
                    for (int c : new int[] {hc, lc}) {
                        if (!comps.contains(c)) {
                            comps.add(c);
                            grew = true;
                        }
                    }
                }
            }
        }
        if (comps.isEmpty()) {
            return null;
        }
        Map<Integer, Point> anchors = new LinkedHashMap<>();
        for (BoundaryLocs.Loc b : placed) {
            Point high = Point.of(b.pos().x(), b.pos().y());
            Point low = low(b);
            for (Point p : new Point[] {high, low}) {
                int c = map.componentId(p.x(), p.y());
                if (comps.contains(c)) {
                    anchors.merge(c, p, (a, q) -> FLANK_ORDER.compare(a, q) <= 0 ? a : q);
                }
            }
        }
        List<Integer> ordered = new ArrayList<>(anchors.keySet());
        ordered.sort(Comparator.comparing(anchors::get, FLANK_ORDER));
        List<Room> rooms = new ArrayList<>();
        for (int c : ordered) {
            rooms.add(new Room(rooms.size(), c, anchors.get(c)));
        }
        Map<Integer, Integer> roomOf = new LinkedHashMap<>();
        for (Room r : rooms) {
            roomOf.put(r.component(), r.index());
        }
        List<Door> doors = new ArrayList<>();
        for (BoundaryLocs.Loc b : placed) {
            Point high = Point.of(b.pos().x(), b.pos().y());
            Point low = low(b);
            Integer hr = roomOf.get(map.componentId(high.x(), high.y()));
            Integer lr = roomOf.get(map.componentId(low.x(), low.y()));
            if (hr != null && lr != null && !hr.equals(lr)) {
                doors.add(new Door(b.id(), high, b.direction(), lr, low, hr, high));
            }
        }
        List<Lever> levers = new ArrayList<>();
        for (int i = 0; i < LEVER_IDS.length; i++) {
            Lever lever = lever(env, map, roomOf, i);
            if (lever == null) {
                throw new IllegalStateException("ERNEST MAZE: lever " + leverName(i) + " (scenery " + LEVER_IDS[i]
                        + ") is not placed in one room of the basement " + anchors.values());
            }
            levers.add(lever);
        }
        return new ErnestBasementMaze(map, rooms, doors, levers);
    }

    private static final Comparator<Point> FLANK_ORDER =
            Comparator.comparingInt(Point::y).thenComparingInt(Point::x);

    private static Point low(BoundaryLocs.Loc b) {
        return b.direction() == 0
                ? Point.of(b.pos().x(), b.pos().y() - 1)
                : Point.of(b.pos().x() - 1, b.pos().y());
    }

    private static @Nullable Lever lever(ServerData env, CollisionMap map, Map<Integer, Integer> roomOf, int i) {
        Lever found = null;
        for (SceneryLocs.Loc s : env.sceneryLocs()) {
            if (s.id() != LEVER_IDS[i]) {
                continue;
            }
            Integer room = null;
            for (Point e : Transport.enumerateEntries(map, s.pos().x(), s.pos().y())) {
                Integer r = roomOf.get(map.componentId(e.x(), e.y()));
                if (r != null) {
                    if (room != null && !room.equals(r)) {
                        return null;
                    }
                    room = r;
                }
            }
            if (room != null) {
                if (found != null) {
                    return null;
                }
                found = new Lever(i, LEVER_IDS[i], Point.of(s.pos().x(), s.pos().y()), room);
            }
        }
        return found;
    }

    public List<Room> rooms() {
        return rooms;
    }

    public List<Door> doors() {
        return doors;
    }

    public List<Lever> levers() {
        return levers;
    }

    /** The room {@code p} is in, or {@code -1} outside the basement. */
    public int roomOf(Point p) {
        Integer r = roomByComponent.get(map.componentId(p.x(), p.y()));
        return r == null ? -1 : r;
    }

    /**
     * The first move of a shortest way from {@code room}, under {@code levers}, into {@code target}, or
     * {@code null} when already there or there is no way. A door is crossed only when every lever it
     * reads is in the {@code sure} mask or has been pulled on the way, and never when its bit (its index
     * in {@link #doors()}) is in {@code excluded}. Ties go to the lower lever, then the earlier door.
     */
    public @Nullable Move next(int room, int levers, int sure, int excluded, int target) {
        int[] path = search(room, levers, sure, excluded, target);
        if (path == null || path.length == 0) {
            return null;
        }
        int move = path[0];
        if (move < LEVERS) {
            return new Pull(this.levers.get(move));
        }
        Door d = doors.get(move - LEVERS);
        return new Cross(d, room, d.across(room));
    }

    /** The number of moves of a shortest way, with every lever trusted, or {@code -1} when none. */
    public int distance(int room, int levers, int target) {
        int[] path = search(room, levers, ALL_LEVERS, 0, target);
        return path == null ? -1 : path.length;
    }

    public int indexOf(Door door) {
        return doors.indexOf(door);
    }

    private @Nullable int[] search(int room, int levers, int sure, int excluded, int target) {
        if (room == target) {
            return new int[0];
        }
        int span = 1 << LEVERS;
        // A state is (room * 64 + levers) * 64 + sure; a move is a lever index, or LEVERS + a door index.
        int states = rooms.size() * span * span;
        int[] parent = new int[states];
        int[] via = new int[states];
        Arrays.fill(parent, -2);
        int start = (room * span + levers) * span + sure;
        parent[start] = -1;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int s = queue.poll();
            int r = s / (span * span), l = (s / span) % span, k = s % span;
            if (r == target) {
                List<Integer> moves = new ArrayList<>();
                for (int at = s; parent[at] >= 0; at = parent[at]) {
                    moves.add(via[at]);
                }
                int[] out = new int[moves.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = moves.get(moves.size() - 1 - i);
                }
                return out;
            }
            for (Lever lv : this.levers) {
                if (lv.room() == r) {
                    int bit = 1 << lv.index();
                    visit(queue, parent, via, s, (r * span + (l ^ bit)) * span + (k | bit), lv.index());
                }
            }
            for (int d = 0; d < doors.size(); d++) {
                Door door = doors.get(d);
                boolean here = door.lowRoom() == r || door.highRoom() == r;
                boolean trusted = (door.reads() & ~k) == 0 && (excluded & (1 << d)) == 0;
                if (here && trusted && door.opens(l)) {
                    visit(queue, parent, via, s, (door.across(r) * span + l) * span + k, LEVERS + d);
                }
            }
        }
        return null;
    }

    private static void visit(ArrayDeque<Integer> queue, int[] parent, int[] via, int from, int to, int move) {
        if (parent[to] == -2) {
            parent[to] = from;
            via[to] = move;
            queue.add(to);
        }
    }
}
