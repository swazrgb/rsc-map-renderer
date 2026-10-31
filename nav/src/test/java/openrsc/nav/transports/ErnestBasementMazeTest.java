package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErnestBasementMazeTest {

    private static final int D = 1 << 3, C = 1 << 2, F = 1 << 5;

    @Test
    @DisplayName("both worlds: seven rooms, nine lever doors, six levers, the stray door 32 left out")
    void derivesTheBasement() {
        for (ServerData env : List.of(RealDefs.serverData(), RealDefs.serverData(RealDefs.COLESLAW))) {
            ErnestBasementMaze maze = ErnestBasementMaze.forWorld(env);
            assertNotNull(maze);
            assertEquals(7, maze.rooms().size(), "rooms " + maze.rooms());
            assertEquals(9, maze.doors().size(), "doors " + maze.doors());
            assertEquals(6, maze.levers().size());
            for (ErnestBasementMaze.Door d : maze.doors()) {
                assertTrue(d.wall().y() > 3370 && d.wall().y() < 3390, "a basement door: " + d);
            }
            // (223,3383) is beside the ladder. A and B are by it, C and D in one room, E and F in another.
            ErnestBasementMaze.Room entry = room(maze, Point.of(223, 3383));
            assertEquals(entry.index(), maze.levers().get(0).room());
            assertEquals(entry.index(), maze.levers().get(1).room());
            assertEquals(maze.levers().get(2).room(), maze.levers().get(3).room());
            assertEquals(maze.levers().get(4).room(), maze.levers().get(5).room());
        }
    }

    @Test
    @DisplayName("every combination asks a lever down: the ladder's removed keys open what all-up opens")
    void removedKeysReadAsAllUp() {
        for (Map.Entry<Integer, List<ErnestBasementMaze.Combination>> e : ErnestBasementMaze.COMBINATIONS.entrySet()) {
            for (ErnestBasementMaze.Combination c : e.getValue()) {
                assertTrue(c.down() != 0, "door " + e.getKey());
            }
        }
    }

    @Test
    @DisplayName("from every state a descent can reach, every room can be reached")
    void everyRoomFromEverywhere() {
        ErnestBasementMaze maze = ErnestBasementMaze.forWorld(RealDefs.serverData());
        int entry = room(maze, Point.of(223, 3383)).index();
        boolean[] seen = new boolean[maze.rooms().size() * 64];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[entry * 64] = true;
        queue.add(entry * 64);
        int reachable = 0;
        while (!queue.isEmpty()) {
            int s = queue.poll();
            reachable++;
            int r = s / 64, l = s % 64;
            for (int target = 0; target < maze.rooms().size(); target++) {
                assertTrue(
                        maze.distance(r, l, target) >= 0, "no way from room " + r + " levers " + l + " to " + target);
            }
            for (ErnestBasementMaze.Lever lv : maze.levers()) {
                if (lv.room() == r) {
                    int t = r * 64 + (l ^ (1 << lv.index()));
                    if (!seen[t]) {
                        seen[t] = true;
                        queue.add(t);
                    }
                }
            }
            for (ErnestBasementMaze.Door d : maze.doors()) {
                if ((d.lowRoom() == r || d.highRoom() == r) && d.opens(l)) {
                    int t = d.across(r) * 64 + l;
                    if (!seen[t]) {
                        seen[t] = true;
                        queue.add(t);
                    }
                }
            }
        }
        assertTrue(reachable > maze.rooms().size(), "the flood went somewhere: " + reachable);
    }

    @Test
    @DisplayName("A wedged maze: north-east room under C, D and F down walks out by 26, 30 and 33, not 31")
    void wedgedMazeWalksOut() {
        ErnestBasementMaze maze = ErnestBasementMaze.forWorld(RealDefs.serverData(RealDefs.COLESLAW));
        int here = maze.roomOf(Point.of(229, 3377));
        int entry = room(maze, Point.of(223, 3383)).index();
        // Under C, D and F down door 31 (D down, B and F up) is locked; 26, 30 and 33 are open.
        int levers = C | D | F;
        ErnestBasementMaze.Cross first = assertInstanceOf(
                ErnestBasementMaze.Cross.class, maze.next(here, levers, ErnestBasementMaze.ALL_LEVERS, 0, entry));
        assertEquals(26, first.door().id());
        assertEquals(3, maze.distance(here, levers, entry));
    }

    @Test
    @DisplayName("in doubt, a door is crossed only once every lever it reads has been re-read by a pull")
    void doubtRereadsBeforeTrusting() {
        ErnestBasementMaze maze = ErnestBasementMaze.forWorld(RealDefs.serverData(RealDefs.COLESLAW));
        int ne = maze.roomOf(Point.of(229, 3377));
        int entry = room(maze, Point.of(223, 3383)).index();
        // Door 26 reads F, so with F alone in doubt it is pulled before 26 is crossed.
        int sure = ErnestBasementMaze.ALL_LEVERS & ~F;
        ErnestBasementMaze.Pull pull =
                assertInstanceOf(ErnestBasementMaze.Pull.class, maze.next(ne, C | D | F, sure, 0, entry));
        assertEquals('F', pull.lever().letter());
        assertEquals(null, maze.next(ne, C | D | F, 0, 0, entry));
    }

    @Test
    @DisplayName("a door refused here is never chosen again from here")
    void refusedDoorsAreExcluded() {
        ErnestBasementMaze maze = ErnestBasementMaze.forWorld(RealDefs.serverData(RealDefs.COLESLAW));
        int ne = maze.roomOf(Point.of(229, 3377));
        int entry = room(maze, Point.of(223, 3383)).index();
        ErnestBasementMaze.Cross first = assertInstanceOf(
                ErnestBasementMaze.Cross.class, maze.next(ne, C | D | F, ErnestBasementMaze.ALL_LEVERS, 0, entry));
        int refused = 1 << maze.indexOf(first.door());
        ErnestBasementMaze.Move next = maze.next(ne, C | D | F, ErnestBasementMaze.ALL_LEVERS, refused, entry);
        assertTrue(
                !(next instanceof ErnestBasementMaze.Cross c)
                        || c.door().id() != first.door().id(),
                "not door " + first.door().id() + " again: " + next);
    }

    @Test
    @DisplayName("one transport into each room, from every other, always open")
    void transports() {
        ServerData env = RealDefs.serverData();
        List<Transport> ts = ErnestBasementMazeTransport.forWorld(env, "ERNEST_BASEMENT_MAZE");
        assertEquals(7, ts.size());
        for (Transport t : ts) {
            assertEquals(6, t.entries().size());
            assertTrue(t.cost() > 0);
            ErnestBasementMazeTransport m = (ErnestBasementMazeTransport) t;
            Point landing = t.destinationFrom(t.entries().get(0));
            assertEquals(m.targetRoom(), m.maze().roomOf(landing));
        }
    }

    private static ErnestBasementMaze.Room room(ErnestBasementMaze maze, Point p) {
        return maze.rooms().get(maze.roomOf(p));
    }
}
