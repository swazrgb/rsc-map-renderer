package openrsc.nav.engine;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Times {@link CollisionMap#canStep} on the real map, one step at a time ({@code step}) and as a
 * breadth-first flood over a 64 by 64 window ({@code flood}). Steps and windows start on walkable
 * ground-floor tiles, every direction equally often.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms4g", "-Xmx4g"})
public class StepBench {

    private static final int ASKS = 1 << 16;
    private static final int MASK = ASKS - 1;
    private static final int WINDOWS = 256;
    private static final int SIZE = 64;

    private CollisionMap map;
    private final int[] fx = new int[ASKS];
    private final int[] fy = new int[ASKS];
    private final int[] tx = new int[ASKS];
    private final int[] ty = new int[ASKS];
    private final int[] windowX = new int[WINDOWS];
    private final int[] windowY = new int[WINDOWS];
    private final int[] queue = new int[SIZE * SIZE];
    private final boolean[] seen = new boolean[SIZE * SIZE];
    private int cursor;
    private int windowCursor;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            map = ServerData.load(conf, world, landscape).collisionMap();
        }
        SplittableRandom rnd = new SplittableRandom(11);
        for (int i = 0; i < ASKS; ) {
            int x = 1 + rnd.nextInt(CollisionMap.WIDTH - 2);
            int y = 1 + rnd.nextInt(CollisionMap.FLOOR_HEIGHT - 2);
            if (map.fullyBlocked(x, y)) {
                continue;
            }
            int d = rnd.nextInt(8);
            int dx = (d < 4 ? d : d + 1) % 3 - 1;
            int dy = (d < 4 ? d : d + 1) / 3 - 1;
            fx[i] = x;
            fy[i] = y;
            tx[i] = x + dx;
            ty[i] = y + dy;
            i++;
        }
        for (int i = 0; i < WINDOWS; ) {
            int x = SIZE + rnd.nextInt(CollisionMap.WIDTH - 2 * SIZE);
            int y = SIZE + rnd.nextInt(CollisionMap.FLOOR_HEIGHT - 2 * SIZE);
            if (map.fullyBlocked(x, y)) {
                continue;
            }
            windowX[i] = x;
            windowY[i] = y;
            i++;
        }
    }

    @Benchmark
    public boolean step() {
        int i = cursor++ & MASK;
        return map.canStep(fx[i], fy[i], tx[i], ty[i], Overlays.NONE);
    }

    /** Counts the cells a flood from a window's centre reaches, testing each step into the cell. */
    @Benchmark
    public int flood() {
        int w = windowCursor++ & (WINDOWS - 1);
        int xMin = windowX[w] - SIZE / 2;
        int yMin = windowY[w] - SIZE / 2;
        java.util.Arrays.fill(seen, false);
        int head = 0;
        int tail = 0;
        int start = (SIZE / 2) * SIZE + SIZE / 2;
        queue[tail++] = start;
        seen[start] = true;
        while (head < tail) {
            int cur = queue[head++];
            int cx = xMin + cur % SIZE;
            int cy = yMin + cur / SIZE;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if ((dx | dy) == 0 || nx < xMin || ny < yMin || nx >= xMin + SIZE || ny >= yMin + SIZE) {
                        continue;
                    }
                    int n = (ny - yMin) * SIZE + (nx - xMin);
                    if (seen[n] || !map.canStep(nx, ny, cx, cy, Overlays.NONE)) {
                        continue;
                    }
                    seen[n] = true;
                    queue[tail++] = n;
                }
            }
        }
        return tail;
    }
}
