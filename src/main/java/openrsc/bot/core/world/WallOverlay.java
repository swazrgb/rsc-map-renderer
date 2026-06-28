package openrsc.bot.core.world;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-bot dynamic wall overlay populated from runtime opcode-91 packets. Walls the OpenRSC server
 * streams as the bot enters regions (Lumbridge castle, swamp fences, Al Kharid building walls) live
 * here rather than in the static landscape, so pathfinding needs to consult both.
 *
 * <p>Mirrors plutonium's {@code localWorld}: per-tile bitmask OR'd on top of
 * the static {@link CollisionMap} flags. Direction → flag mapping follows the OpenRSC server's
 * BOUNDARY handling:
 * <ul>
 *   <li>dir 0 — horizontal wall: WALL_NORTH on (x,y), WALL_SOUTH on (x,y-1)</li>
 *   <li>dir 1 — vertical wall: WALL_EAST on (x,y), WALL_WEST on (x-1,y)</li>
 *   <li>dir 2 — diagonal NE-SW: FULL_BLOCK_A on (x,y)</li>
 *   <li>dir 3 — diagonal NW-SE: FULL_BLOCK_B on (x,y)</li>
 * </ul>
 */
public final class WallOverlay {

  private final ConcurrentHashMap<Long, Byte> tiles = new ConcurrentHashMap<>();

  /**
   * Exact-negative guard in front of {@link #tiles}: one bit per 8×8 map chunk (the same chunking
   * {@link #clearChunk} receives), set when any tile in the chunk has ever held overlay flags.
   * {@link #flags} reads the bit before touching the map — the pathfinder calls it for every tile
   * flag read (~10⁶ per long route), and the boxed-Long CHM lookup was half of all pathfinding CPU
   * and 94% of its allocation (JFR, 2026-06). A clear bit means a guaranteed-empty chunk; a set bit
   * falls through to the map ({@link #andNot} leaves bits set — stale positives cost a lookup,
   * never correctness). Out-of-grid coords (e.g. {@code or(x-1, …)} at x=0) skip the guard
   * entirely.
   *
   * <p>Plain array, no volatile: the overlay is written and read by the
   * same account-loop thread (the bits are populated before any read that could need them); an
   * AtomicLongArray here failed to inline and showed up at 22% of samples. Racing cross-thread
   * readers (none today beyond diagnostics) would see a stale 0 → static flags only, the same
   * benign race the pre-guard CHM read already had.
   */
  private final long[] chunkBits = new long[(CHUNKS_X * CHUNKS_Y + 63) >> 6];

  private static final int CHUNKS_X = (CollisionMap.WIDTH + 7) >> 3;
  private static final int CHUNKS_Y = (CollisionMap.HEIGHT + 7) >> 3;

  private static boolean inGrid(int x, int y) {
    return x >= 0 && y >= 0 && x < CollisionMap.WIDTH && y < CollisionMap.HEIGHT;
  }

  private static int chunkIndex(int x, int y) {
    return (y >> 3) * CHUNKS_X + (x >> 3);
  }

  /**
   * Bumped once per mutating call. Lets consumers (the pathfinder's leg caches) memoize results
   * that are pure functions of overlay state and invalidate on any wall change without diffing
   * tiles. Writes come from the account loop only; readers just compare two observed values, so a
   * plain volatile increment is sufficient.
   */
  private volatile long generation;

  /**
   * Monotonic change counter — unequal values mean the overlay may have changed; equal values (same
   * instance) mean it definitely hasn't.
   */
  public long generation() {
    return generation;
  }

  private static long key(int x, int y) {
    return ((long) x << 32) | (y & 0xFFFFFFFFL);
  }

  /**
   * Add the wall mask for a boundary at {@code (x, y)} with the given direction.
   */
  public void addBoundary(int x, int y, int dir) {
    generation++;
    switch (dir) {
      case 0 -> {
        or(x, y, CollisionMap.WALL_NORTH);
        or(x, y - 1, CollisionMap.WALL_SOUTH);
      }
      case 1 -> {
        or(x, y, CollisionMap.WALL_EAST);
        or(x - 1, y, CollisionMap.WALL_WEST);
      }
      case 2 -> or(x, y, CollisionMap.FULL_BLOCK_A);
      case 3 -> or(x, y, CollisionMap.FULL_BLOCK_B);
      default -> { /* ignore */ }
    }
  }

  /**
   * Remove the wall mask for a boundary that the server just despawned (id=60000).
   */
  public void removeBoundary(int x, int y, int dir) {
    generation++;
    switch (dir) {
      case 0 -> {
        andNot(x, y, CollisionMap.WALL_NORTH);
        andNot(x, y - 1, CollisionMap.WALL_SOUTH);
      }
      case 1 -> {
        andNot(x, y, CollisionMap.WALL_EAST);
        andNot(x - 1, y, CollisionMap.WALL_WEST);
      }
      case 2 -> andNot(x, y, CollisionMap.FULL_BLOCK_A);
      case 3 -> andNot(x, y, CollisionMap.FULL_BLOCK_B);
      default -> { /* ignore */ }
    }
  }

  /**
   * Stamp a type=1 (solid) scenery's footprint as {@link CollisionMap#FULL_BLOCK_C} — the exact bit
   * the server's {@code World.registerGameObject} sets, so diagonal pass-through rules treat a
   * dynamic spawn identically to a static one. Footprint width/height swap when {@code dir} is not
   * 0/4, mirroring {@code CollisionMap.applyScenery}.
   */
  public void addSceneryFullBlock(int originX, int originY, int dir, int defW, int defH) {
    generation++;
    applyFullBlock(originX, originY, dir, defW, defH, true);
  }

  /**
   * Inverse of {@link #addSceneryFullBlock} — the server despawned it.
   */
  public void removeSceneryFullBlock(int originX, int originY, int dir, int defW, int defH) {
    generation++;
    applyFullBlock(originX, originY, dir, defW, defH, false);
  }

  private void applyFullBlock(int dx, int dy, int dir, int defW, int defH, boolean stamp) {
    int w = defW, h = defH;
    if (dir != 0 && dir != 4) {
      int t = w;
      w = h;
      h = t;
    }
    for (int x = dx; x < dx + w; x++) {
      for (int y = dy; y < dy + h; y++) {
        if (stamp) {
          or(x, y, CollisionMap.FULL_BLOCK_C);
        } else {
          andNot(x, y, CollisionMap.FULL_BLOCK_C);
        }
      }
    }
  }

  /**
   * Stamp the walls of a type=2 scenery (gate / door) into the overlay. Mirrors
   * {@code CollisionMap.applyScenery} so a closed door looks identical to a static wall to the
   * pathfinder.
   */
  public void addScenery(int originX, int originY, int dir, int defW, int defH) {
    generation++;
    applyScenery(originX, originY, dir, defW, defH, true);
  }

  /**
   * Inverse of {@link #addScenery} — clear the walls when a door opens.
   */
  public void removeScenery(int originX, int originY, int dir, int defW, int defH) {
    generation++;
    applyScenery(originX, originY, dir, defW, defH, false);
  }

  private void applyScenery(int dx, int dy, int dir, int defW, int defH, boolean stamp) {
    int w = defW, h = defH;
    if (dir != 0 && dir != 4) {
      int t = w;
      w = h;
      h = t;
    }
    for (int x = dx; x < dx + w; x++) {
      for (int y = dy; y < dy + h; y++) {
        switch (dir) {
          case 0 -> {
            if (stamp) {
              or(x, y, CollisionMap.WALL_EAST);
            } else {
              andNot(x, y, CollisionMap.WALL_EAST);
            }
            if (stamp) {
              or(x - 1, y, CollisionMap.WALL_WEST);
            } else {
              andNot(x - 1, y, CollisionMap.WALL_WEST);
            }
          }
          case 2 -> {
            if (stamp) {
              or(x, y, CollisionMap.WALL_SOUTH);
            } else {
              andNot(x, y, CollisionMap.WALL_SOUTH);
            }
            if (stamp) {
              or(x, y + 1, CollisionMap.WALL_NORTH);
            } else {
              andNot(x, y + 1, CollisionMap.WALL_NORTH);
            }
          }
          case 4 -> {
            if (stamp) {
              or(x, y, CollisionMap.WALL_WEST);
            } else {
              andNot(x, y, CollisionMap.WALL_WEST);
            }
            if (stamp) {
              or(x + 1, y, CollisionMap.WALL_EAST);
            } else {
              andNot(x + 1, y, CollisionMap.WALL_EAST);
            }
          }
          case 6 -> {
            if (stamp) {
              or(x, y, CollisionMap.WALL_NORTH);
            } else {
              andNot(x, y, CollisionMap.WALL_NORTH);
            }
            if (stamp) {
              or(x, y - 1, CollisionMap.WALL_SOUTH);
            } else {
              andNot(x, y - 1, CollisionMap.WALL_SOUTH);
            }
          }
          default -> { /* unknown dir — leave alone */ }
        }
      }
    }
  }

  /**
   * Extra flags overlaid on the static map for {@code (x, y)}.
   */
  public int flags(int x, int y) {
    if (inGrid(x, y)) {
      int chunk = chunkIndex(x, y);
      if ((chunkBits[chunk >> 6] & (1L << chunk)) == 0) {
        return 0;
      }
    }
    Byte b = tiles.get(key(x, y));
    return b == null ? 0 : b & 0xFF;
  }

  public int size() {
    return tiles.size();
  }

  /**
   * True if any overlay tile may fall inside the inclusive bbox (chunk granularity — may return a
   * false positive for a chunk whose tiles were all {@link #andNot}-cleared, never a false
   * negative). Search loops call this once per window and drop the overlay reference entirely when
   * it cannot matter — the per-tile {@link #flags} consult inside A* expansion was ~half of
   * pathfinding CPU even as a bit test. Callers must pad the bbox by 1: flank/diagonal legality
   * reads tiles one step outside the expansion window.
   */
  public boolean intersects(int x0, int y0, int x1, int y1) {
    int cx0 = Math.max(0, x0) >> 3, cx1 = Math.min(CollisionMap.WIDTH - 1, x1) >> 3;
    int cy0 = Math.max(0, y0) >> 3, cy1 = Math.min(CollisionMap.HEIGHT - 1, y1) >> 3;
    for (int cy = cy0; cy <= cy1; cy++) {
      for (int cx = cx0; cx <= cx1; cx++) {
        int chunk = cy * CHUNKS_X + cx;
        if ((chunkBits[chunk >> 6] & (1L << chunk)) != 0) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Drop every flag byte whose tile falls inside the 8×8 chunk {@code (chunkX, chunkZ)}. Called
   * from the opcode-211 chunk-clear path so far-region wall stamps don't survive in the overlay
   * after they've left the server's view.
   */
  public void clearChunk(int chunkX, int chunkZ) {
    generation++;
    tiles.keySet().removeIf(k -> {
      int x = (int) (k >> 32);
      int y = (int) (k & 0xFFFFFFFFL);
      return (x >> 3) == chunkX && (y >> 3) == chunkZ;
    });
    if (chunkX >= 0 && chunkX < CHUNKS_X && chunkZ >= 0 && chunkZ < CHUNKS_Y) {
      int chunk = chunkZ * CHUNKS_X + chunkX;
      chunkBits[chunk >> 6] &= ~(1L << chunk);
    }
  }

  /**
   * Diagnostic — list all non-empty tiles inside the bounding box.
   */
  public String dump(int x0, int y0, int x1, int y1) {
    StringBuilder sb = new StringBuilder();
    sb.append("WallOverlay non-empty tiles in [").append(x0).append(',').append(y0)
        .append(' ').append('-').append(' ').append(x1).append(',').append(y1).append("]:\n");
    int n = 0;
    for (var e : tiles.entrySet()) {
      long k = e.getKey();
      int x = (int) (k >> 32);
      int y = (int) (k & 0xFFFFFFFFL);
      if (x < x0 || x > x1 || y < y0 || y > y1) {
        continue;
      }
      sb.append("  (").append(x).append(',').append(y).append(") = 0x")
          .append(String.format("%02x", e.getValue() & 0xFF)).append('\n');
      if (++n > 200) {
        sb.append("  ...truncated\n");
        break;
      }
    }
    if (n == 0) {
      sb.append("  (empty)\n");
    }
    return sb.toString();
  }

  private void or(int x, int y, int mask) {
    if (inGrid(x, y)) {
      // Set the guard bit BEFORE the map write so a racing reader can
      // never see the entry without the bit. Plain read-modify-write:
      // mutations come from the single account loop (see generation).
      int chunk = chunkIndex(x, y);
      chunkBits[chunk >> 6] |= 1L << chunk;
    }
    tiles.merge(key(x, y), (byte) mask,
        (oldVal, addVal) -> (byte) ((oldVal & 0xFF) | (addVal & 0xFF)));
  }

  private void andNot(int x, int y, int mask) {
    tiles.computeIfPresent(key(x, y), (k, v) -> {
      int nv = (v & 0xFF) & ~mask;
      return nv == 0 ? null : (byte) nv;
    });
  }
}
