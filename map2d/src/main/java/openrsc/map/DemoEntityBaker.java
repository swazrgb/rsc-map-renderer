package openrsc.map;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import openrsc.gamedata.NpcDefs;
import openrsc.gamedata.runtime.GameEnvironment;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.NpcLocs;
import openrsc.gamedata.ServerConf;
import tools.jackson.databind.ObjectMapper;

/**
 * Bakes collision-aware NPC/player wander tracks for the open-source 3D viewer
 * demo, so it shows a believable living world instead of hand-authored dummies.
 *
 * <p>Lives in {@code map2d} because it needs the {@link CollisionMap} (built
 * from the JAG landscape + boundary/scenery/tile/door defs). Run via
 * {@code java -cp <map2d-fat-jar> openrsc.map.DemoEntityBaker <siteRoot>} — the
 * private bot build never pulls this in.
 *
 * <p>Each entity roams like the server does: pick a random reachable target
 * within radius 8 of its current tile (clamped to its spawn roam box), BFS a
 * collision-correct path there one tile per tick, pause now and then, and walk
 * home at the end so the loop is seamless. EVERY spawn is simulated, so with the
 * viewer's "simulation" toggle on (default) the whole world is alive and no
 * spawn ghosts show; toggling it off drops the observers and reveals the raw
 * {@code /api/npc-spawns} table instead.
 *
 * <p>Output {@code <siteRoot>/api/demo/entities.json}: per entity a start tile
 * and a compact per-tick delta string (each char encodes one 8-direction step
 * or a pause), which the viewer expands into a looping position track.
 */
public final class DemoEntityBaker {

  /** Shared, thread-safe mapper — one instance avoids re-building serializers per bake. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final int TICK_MS = 640;        // authentic RSC server tick
  private static final int WANDER_TICKS = 469;   // ~5 min at 640ms before looping
  private static final int RADIUS = 8;           // server walkablePoint radius
  // Server NpcBehavior.setRoaming: a roam decision fires only when >= 5 ticks
  // have passed since the last one AND the previous path finished, and even then
  // it's a coin-flip whether the NPC actually walks. Mirroring this is what keeps
  // the demo from wandering far more than a live server.
  private static final int MOVE_COOLDOWN = 5;
  private static final long WANDER_SEED = 0x0DDBA11L;

  /** Player appearances placed around Lumbridge (must match the set world3d-bake
   *  pre-bakes — see Bake.DEMO_TOKENS). Layers|colours, exactly as the wire. */
  // Layer value = animation id + 1. Base parts: 1=head1 2=body1(male) 3=legs1
  // 4=fhead1 5=fbody1(female). Keep armour/weapon out of the body slots — a
  // stray body sprite in another slot double-draws a chest.
  private static final String[] PLAYER_APPEARANCES = {
      "1,34,42,103,53,75,0,0,47,12,0,63|0,8,14,0", // full rune kit (male)
      "1,2,3|3,0,11,2",                            // plain male, head1
      "6,2,3|6,9,4,1",                             // plain male, head2
      "4,5,3|1,12,2,3",                            // plain female (fhead1 + fbody1)
  };
  /** Lumbridge-ish seed tiles (folded z, floor 0) for the demo players; each is
   *  snapped to the nearest walkable tile. Spread out so they don't cluster. */
  private static final int[][] PLAYER_ANCHORS = {
      {122, 648}, {135, 655}, {115, 660}, {128, 640},
  };

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: DemoEntityBaker <siteRoot>");
      System.exit(2);
      return;
    }
    Path out = new File(args[0]).toPath().resolve("api").resolve("demo");
    Files.createDirectories(out);

    ServerConf conf = ServerConf.resolve();
    GameEnvironment env = GameEnvironment.loadDefault(conf);
    CollisionMap cm = env.collisionMap();
    List<NpcLocs.Spawn> spawns = env.npcLocs();
    NpcDefs defs = env.npcDefs();

    // Every spawn is simulated (100%), so with the viewer's simulation toggle on
    // the whole world is alive and no ghosts show.
    List<Track> npcs = new ArrayList<>();
    Map<Integer, String> names = new LinkedHashMap<>();
    int stationary = 0;
    for (int i = 0; i < spawns.size(); i++) {
      NpcLocs.Spawn s = spawns.get(i);
      int sx = s.start().x();
      int sz = s.start().z();
      if (!cm.inBounds(sx, sz)) {
        continue; // off-map spawn; nothing to place
      }
      List<int[]> track;
      if (cm.fullyBlocked(sx, sz)) {
        // Data-quirk spawn on a blocked tile: stand still (can't wander), but
        // still baked so it's "observed" and never falls back to a ghost.
        track = List.of(new int[]{sx, sz});
        stationary++;
      } else {
        track = simulate(cm, sx, sz,
            Math.min(s.min().x(), s.max().x()), Math.min(s.min().z(), s.max().z()),
            Math.max(s.min().x(), s.max().x()), Math.max(s.min().z(), s.max().z()),
            new Random(WANDER_SEED + i));
      }
      npcs.add(new Track(i, s.id(), null, sx, sz, encode(track)));
      NpcDefs.Def def = defs.get(s.id());
      if (def != null && def.name() != null) {
        names.putIfAbsent(s.id(), def.name());
      }
    }

    // Players: a few around Lumbridge, wandering a small area, solid sprites.
    List<Track> players = new ArrayList<>();
    for (int p = 0; p < PLAYER_ANCHORS.length; p++) {
      int[] tile = findWalkable(cm, PLAYER_ANCHORS[p][0], PLAYER_ANCHORS[p][1]);
      if (tile == null) {
        continue;
      }
      int ax = tile[0];
      int az = tile[1];
      List<int[]> track = simulate(cm, ax, az, ax - 6, az - 6, ax + 6, az + 6,
          new Random(WANDER_SEED * 7 + p));
      players.add(new Track(30000 + p, 0, PLAYER_APPEARANCES[p % PLAYER_APPEARANCES.length],
          ax, az, encode(track)));
    }

    // Self-check: re-derive positions from the encoded delta strings exactly as
    // the viewer will, and prove every step is collision-legal (canStep) and no
    // tile is blocked. Catches both wander bugs and encode/decode mismatches.
    long badSteps = 0;
    for (Track tr : npcs) {
      int x = tr.x();
      int z = tr.z();
      for (int t = 0; t < tr.d().length(); t++) {
        int s = tr.d().charAt(t) - '0';
        int nx = x + s / 3 - 1;
        int nz = z + s % 3 - 1;
        // Only actual moves must be legal (canStep already forbids stepping
        // onto a blocked tile); a stationary NPC may sit on a data-quirk tile.
        if (!(nx == x && nz == z) && !cm.canStep(x, z, nx, nz)) {
          badSteps++;
        }
        x = nx;
        z = nz;
      }
    }
    if (badSteps > 0) {
      throw new IllegalStateException("wander self-check FAILED: " + badSteps + " illegal steps");
    }
    System.out.println("wander self-check: " + npcs.size() + " tracks, all steps collision-legal");

    write(out.resolve("entities.json"), names, npcs, players);
    System.out.println("demo entities: " + npcs.size() + " live npcs (all spawns, "
        + stationary + " stationary on blocked tiles), " + players.size()
        + " players -> " + out.resolve("entities.json"));
  }

  private record Track(int serverIndex, int id, String appearance, int x, int z, String d) {}

  /** One wander track: positions per tick, looping back to the start tile. */
  private static List<int[]> simulate(CollisionMap cm, int sx, int sz,
      int minx, int minz, int maxx, int maxz, Random rng) {
    List<int[]> pos = new ArrayList<>(WANDER_TICKS + 32);
    int x = sx;
    int z = sz;
    Deque<int[]> path = new ArrayDeque<>();
    int since = MOVE_COOLDOWN; // allow an immediate first decision
    for (int t = 0; t < WANDER_TICKS; t++) {
      pos.add(new int[]{x, z});
      since++;
      if (!path.isEmpty()) {
        int[] step = path.pollFirst();
        x = step[0];
        z = step[1];
      } else if (since >= MOVE_COOLDOWN) {
        // Roam decision: the timer resets whether or not we move (as the server
        // sets lastMovement on every decision), so a "stay" still costs a cooldown.
        since = 0;
        if (rng.nextInt(2) == 0) { // 50% chance to walk (server: random(0,1)==1)
          int tx = randIn(rng, Math.max(minx, x - RADIUS), Math.min(maxx, x + RADIUS));
          int tz = randIn(rng, Math.max(minz, z - RADIUS), Math.min(maxz, z + RADIUS));
          List<int[]> p = bfs(cm, x, z, tx, tz, minx, minz, maxx, maxz);
          if (p != null) {
            path.addAll(p);
          }
        }
      }
    }
    // The loop records pos[t] then advances, so the final (x,z) is one step
    // past the last recorded tile — record it before appending the home path,
    // else pos[last]->home[0] skips a tile and clampStep fakes a diagonal.
    pos.add(new int[]{x, z});
    // Try to walk home (wider bound to improve success), then truncate to the
    // last tick the entity was actually AT its start tile, so the loop closes
    // with a real zero-delta step — never a fabricated teleport across a wall.
    List<int[]> home = bfs(cm, x, z, sx, sz, minx - 24, minz - 24, maxx + 24, maxz + 24);
    if (home != null) {
      pos.addAll(home);
    }
    int last = 0;
    for (int i = pos.size() - 1; i > 0; i--) {
      if (pos.get(i)[0] == sx && pos.get(i)[1] == sz) {
        last = i;
        break;
      }
    }
    return new ArrayList<>(pos.subList(0, last + 1));
  }

  private static int randIn(Random rng, int lo, int hi) {
    return lo >= hi ? lo : lo + rng.nextInt(hi - lo + 1);
  }

  /** Nearest walkable tile to (cx,cz) by outward ring search; null if none near. */
  private static int[] findWalkable(CollisionMap cm, int cx, int cz) {
    for (int r = 0; r <= 12; r++) {
      for (int dz = -r; dz <= r; dz++) {
        for (int dx = -r; dx <= r; dx++) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
            continue;
          }
          int x = cx + dx;
          int z = cz + dz;
          if (cm.inBounds(x, z) && !cm.fullyBlocked(x, z)) {
            return new int[]{x, z};
          }
        }
      }
    }
    return null;
  }

  /** 8-connected BFS gated by canStep, bounded to the roam box (+1 margin).
   *  Returns the tiles to walk (excluding the start), or null if unreachable. */
  private static List<int[]> bfs(CollisionMap cm, int sx, int sz, int tx, int tz,
      int minx, int minz, int maxx, int maxz) {
    if (sx == tx && sz == tz) {
      return null;
    }
    if (cm.fullyBlocked(tx, tz)) {
      return null;
    }
    int lox = minx - 1;
    int loz = minz - 1;
    int hix = maxx + 1;
    int hiz = maxz + 1;
    int w = hix - lox + 1;
    int h = hiz - loz + 1;
    if (w <= 0 || h <= 0 || w * h > 40000) {
      return null;
    }
    int[] prev = new int[w * h];
    java.util.Arrays.fill(prev, -2);
    Deque<int[]> q = new ArrayDeque<>();
    q.add(new int[]{sx, sz});
    prev[(sz - loz) * w + (sx - lox)] = -1;
    int[] dxs = {1, -1, 0, 0, 1, 1, -1, -1};
    int[] dzs = {0, 0, 1, -1, 1, -1, 1, -1};
    while (!q.isEmpty()) {
      int[] c = q.pollFirst();
      int cx = c[0];
      int cz = c[1];
      if (cx == tx && cz == tz) {
        // reconstruct
        List<int[]> path = new ArrayList<>();
        int px = cx;
        int pz = cz;
        while (px != sx || pz != sz) {
          path.add(0, new int[]{px, pz});
          int idx = prev[(pz - loz) * w + (px - lox)];
          px = lox + idx % w;
          pz = loz + idx / w;
        }
        return path;
      }
      for (int d = 0; d < 8; d++) {
        int nx = cx + dxs[d];
        int nz = cz + dzs[d];
        if (nx < lox || nx > hix || nz < loz || nz > hiz) {
          continue;
        }
        int ni = (nz - loz) * w + (nx - lox);
        if (prev[ni] != -2 || !cm.canStep(cx, cz, nx, nz)) {
          continue;
        }
        prev[ni] = (cz - loz) * w + (cx - lox);
        q.add(new int[]{nx, nz});
      }
    }
    return null;
  }

  /** Encode the per-tick transitions as one char each: (dx+1)*3 + (dz+1) + '0'.
   *  The final char loops the last tile back to the first (both == start). */
  private static String encode(List<int[]> pos) {
    StringBuilder sb = new StringBuilder(pos.size());
    for (int t = 0; t < pos.size(); t++) {
      int[] a = pos.get(t);
      int[] b = pos.get((t + 1) % pos.size());
      int dx = clampStep(b[0] - a[0]);
      int dz = clampStep(b[1] - a[1]);
      sb.append((char) ('0' + (dx + 1) * 3 + (dz + 1)));
    }
    return sb.toString();
  }

  private static int clampStep(int d) {
    return d < -1 ? -1 : d > 1 ? 1 : d;
  }

  /** Serialized shapes (record component names == JSON field names). */
  private record NpcOut(int si, int id, int x, int z, String d) {}

  private record PlayerOut(int si, String a, int x, int z, String d) {}

  private record Entities(int tickMs, Map<Integer, String> names,
                          List<NpcOut> npcs, List<PlayerOut> players) {}

  private static void write(Path file, Map<Integer, String> names,
      List<Track> npcs, List<Track> players) throws IOException {
    List<NpcOut> npcOut = new ArrayList<>(npcs.size());
    for (Track t : npcs) {
      npcOut.add(new NpcOut(t.serverIndex(), t.id(), t.x(), t.z(), t.d()));
    }
    List<PlayerOut> playerOut = new ArrayList<>(players.size());
    for (Track t : players) {
      playerOut.add(new PlayerOut(t.serverIndex(), t.appearance(), t.x(), t.z(), t.d()));
    }
    MAPPER.writeValue(file.toFile(),
        new Entities(TICK_MS, names, npcOut, playerOut));
  }

  private DemoEntityBaker() {}
}
