package openrsc.bot.core.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import openrsc.bot.api.geometry.Area;
import openrsc.bot.api.geometry.Point;
import openrsc.bot.core.defs.BoundaryLocs;
import openrsc.bot.core.defs.DoorDefs;
import openrsc.bot.core.defs.ObjectDefs;
import openrsc.bot.core.defs.SceneryLocs;
import openrsc.bot.core.defs.TileDefs;
import openrsc.bot.core.world.jag.JagLandscape;
import openrsc.bot.core.world.jag.JagLandscape.RawSector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static ground-floor walkability bitmap, derived once at startup from the RSC landscape archive
 * ({@code Authentic_Landscape.orsc}) plus the door and tile def tables. Mirrors plutonium
 * {@code world.go} but limited to floor 0 (the ground plane scripts will path on).
 *
 * <p>Each tile holds an 8-bit flag mask:
 * <pre>
 *   bit 0 (0x01)  wallNorth   — blocks moving north INTO this tile (or south OUT of (x,z+1))
 *   bit 1 (0x02)  wallEast    — blocks moving east INTO this tile
 *   bit 2 (0x04)  wallSouth   — blocks moving south INTO this tile
 *   bit 3 (0x08)  wallWest    — blocks moving west INTO this tile
 *   bit 4 (0x10)  fullBlockA  — diagonal full-block A
 *   bit 5 (0x20)  fullBlockB  — diagonal full-block B
 *   bit 6 (0x40)  fullBlockC  — ground-overlay block (water, etc.)
 * </pre>
 * Bits map to plutonium's wall* / fullBlock* constants (world.go:23-29) — the pathfinder consults
 * these via {@link #blockedDir} helpers.
 */
public final class CollisionMap {

  private static final Logger LOG = LoggerFactory.getLogger(CollisionMap.class);

  // Wire format constants from plutonium world.go.
  public static final int WALL_NORTH = 0x01;
  public static final int WALL_EAST = 0x02;
  public static final int WALL_SOUTH = 0x04;
  public static final int WALL_WEST = 0x08;
  public static final int FULL_BLOCK_A = 0x10;
  public static final int FULL_BLOCK_B = 0x20;
  public static final int FULL_BLOCK_C = 0x40;
  public static final int FULL_BLOCK = FULL_BLOCK_A | FULL_BLOCK_B | FULL_BLOCK_C;

  /**
   * Object/door names whose tiles the server marks {@code projectileAllowed} (line of sight passes
   * over them). Verbatim, lower-cased copy of server {@code Constants.objectsProjectileClipAllowed}
   * — kept in sync by {@link openrsc.bot.core.world.CollisionMapServerParityTest} which transcribes
   * the same source independently. Compared case-insensitively (server uses {@code equalsIgnoreCase}).
   */
  private static final Set<String> PROJECTILE_CLIP_ALLOWED_NAMES = Set.of(
      "gravestone", "sign", "broken pillar", "bone", "animalskull", "skull", "egg", "eggs", "ladder",
      "torch", "rock", "treestump", "railing", "railings", "gate", "fence", "table", "smashed chair",
      "smashed table", "longtable", "wooden gate", "metal gate", "chair");

  // Landscape archive layout. X range mirrors plutonium world.go; the Y
  // range mirrors the SERVER's WorldLoader.loadWorld, which loops
  // `sy < 944` step 48 → sector rows 37..56 (20 rows) per floor. We used
  // to load row 57 as well — a sector the server never reads — which left
  // ~48 rows of phantom-walkable stale terrain per floor seam (bot map
  // walkable, server FULL_BLOCK). See pathfinder review 2026-06.
  static final int REGION_SIZE = 48;
  static final int LOAD_REGION_X_FROM = 48;
  static final int LOAD_REGION_X_TO = 68;
  static final int LOAD_REGION_Y_FROM = 37;
  static final int LOAD_REGION_Y_TO = 56;
  static final int REGIONS_X = LOAD_REGION_X_TO - LOAD_REGION_X_FROM + 1; // 21
  static final int REGIONS_Y = LOAD_REGION_Y_TO - LOAD_REGION_Y_FROM + 1; // 20
  static final int TILE_BYTES = 10; // groundEl, groundTex, groundOv, roofTex, hwall, vwall, 4×diagonalWalls

  /**
   * Number of vertical floor planes (0=ground, 1/2=upper, 3=basement).
   */
  public static final int FLOOR_COUNT = 4;
  /**
   * Y offset per floor — RSC encodes upper-floor tiles at {@code y + 944*floor}.
   */
  public static final int FLOOR_HEIGHT = 944;
  /**
   * Width in absolute world tiles (same across floors).
   */
  public static final int WIDTH = REGIONS_X * REGION_SIZE;
  /**
   * Height covering all 4 floors. Floor n occupies y = n*FLOOR_HEIGHT .. n*FLOOR_HEIGHT +
   * REGIONS_Y*REGION_SIZE.
   */
  public static final int HEIGHT = FLOOR_COUNT * FLOOR_HEIGHT;

  private final byte[] flags = new byte[WIDTH * HEIGHT];
  /**
   * Server {@code TileValue.projectileAllowed} per tile — true where a projectile / line of sight
   * passes <em>over</em> the tile even though it may block walking. Set by the server (and mirrored
   * here in {@link #applySceneryProjectileClip} / {@link #applyBoundaryProjectileClip}) for
   * projectile-clip scenery: every 1×1 non-chest non-tree object (e.g. an anvil), the named
   * allowlist (signs, railings, gates, fences, tables…), and doorType-1 boundaries on the allowlist.
   *
   * <p>This is what makes the server's LOS check ({@code PathValidation.checkAdjacentDistance} via
   * {@code checkBlockingDistance}) differ from its walking check ({@code checkAdjacent}): the former
   * treats a {@code projectileAllowed} tile as clear, the latter does not. The bot's walking
   * collision ({@link #canStep}) ignores this field entirely — only {@link #checkPath} (trade /
   * ranged / magic / duel line-of-sight) consults it via {@link #checkProjectileStep}. Default false
   * (server {@code TileValue} default), so a tile that's never stamped behaves exactly as before.
   */
  private final boolean[] projectileAllowed = new boolean[WIDTH * HEIGHT];
  /**
   * Static connected-component id per tile, 8-connected over {@link #canStep} with no overlay. 0 =
   * unwalkable (fully blocked) or out-of-bounds. Computed once after all flag stamping; see
   * {@link #computeComponents}.
   */
  private final short[] components = new short[WIDTH * HEIGHT];
  private int componentCount;

  /**
   * Raw {@code groundTexture} byte per tile from the landscape archive. Indexes the 256-entry biome
   * palette (0-63 water, 64-127 grass, 128-191 sand, 192-255 dirt — see stock client
   * {@code orsc/graphics/three/World.java:60-74}). Used by the web UI's terrain renderer;
   * pathfinding ignores this.
   */
  private final byte[] groundTexture = new byte[WIDTH * HEIGHT];
  /**
   * Raw {@code groundOverlay} byte per tile (1-based id into TileDefs; 0 = no overlay). Same
   * archive source as {@link #groundTexture}.
   */
  private final byte[] groundOverlay = new byte[WIDTH * HEIGHT];
  /**
   * Raw {@code roofTexture} byte per tile from the landscape archive. The stock client uses F0's
   * value as its {@code hasRoofTile} rendering gate for upper floors; we keep it for the web UI's
   * renderer only. Pathfinding deliberately does NOT gate F1+ walkability on it — the server
   * permits walking upper-floor void ({@code ServerAcceptsVoidWalkIT}), and a roof gate would be
   * more restrictive than the server.
   */
  private final byte[] roofTexture = new byte[WIDTH * HEIGHT];

  private CollisionMap() {
    // Mirror server TileValue defaults: every tile starts FULL_BLOCK_C
    // (void / no section data). loadSection clears this for each tile in
    // a present section, leaving missing sections (upper-floor void)
    // permanently blocked — matches OpenRSC server behaviour, where
    // TileValue.traversalMask = CollisionFlag.FULL_BLOCK until a sector
    // overwrites it. Without this, A* freely explored upper-floor void
    // tiles (no archive entry → flags stayed 0 → walkable).
    Arrays.fill(flags, (byte) FULL_BLOCK_C);
  }

  public static CollisionMap load(Path landscapeZip, DoorDefs doorDefs, TileDefs tileDefs)
      throws IOException {
    return load(landscapeZip, doorDefs, tileDefs, null, null, null);
  }

  /**
   * Load landscape walls + optionally stamp scenery blocks and boundary walls. Pass {@code null}
   * for any optional argument to skip that pass (e.g. tests that only need the static landscape
   * grid).
   */
  public static CollisionMap load(Path landscapeZip, DoorDefs doorDefs, TileDefs tileDefs,
      List<SceneryLocs.Loc> scenery, ObjectDefs objectDefs,
      List<BoundaryLocs.Loc> boundaries) throws IOException {
    CollisionMap m = new CollisionMap();
    try (ZipFile zip = new ZipFile(landscapeZip.toFile())) {
      for (int floor = 0; floor < FLOOR_COUNT; floor++) {
        int missing = 0;
        for (int rx = LOAD_REGION_X_FROM; rx <= LOAD_REGION_X_TO; rx++) {
          for (int ry = LOAD_REGION_Y_FROM; ry <= LOAD_REGION_Y_TO; ry++) {
            String name = "h" + floor + "x" + rx + "y" + ry;
            ZipEntry e = zip.getEntry(name);
            if (e == null) {
              missing++;
              continue;
            }
            try (InputStream in = zip.getInputStream(e)) {
              m.loadSection(in, rx, ry, floor, doorDefs, tileDefs);
            }
          }
        }
        if (missing > 0) {
          LOG.debug("landscape floor {}: {} missing sections (expected on sparse upper floors)",
              floor, missing);
        }
      }
    }
    m.finishLoad(scenery, objectDefs, boundaries, doorDefs);
    return m;
  }

  /**
   * Load collision from the classic RSC map archives ({@code maps{rev}.jag/.mem}), the source the
   * OpenRSC server actually paths against when {@code based_map_data >= 28}. Same region iteration,
   * floor offsets and per-tile stamping as {@link #load}; only the per-sector decode differs (JAG
   * container vs {@code .orsc} ZIP). Use this for authentic servers (Uranium); keep {@link #load}
   * for custom-server {@code .orsc} landscapes.
   */
  public static CollisionMap loadFromJag(JagLandscape landscape,
      DoorDefs doorDefs, TileDefs tileDefs,
      List<SceneryLocs.Loc> scenery, ObjectDefs objectDefs,
      List<BoundaryLocs.Loc> boundaries) {
    CollisionMap m = new CollisionMap();
    for (int floor = 0; floor < FLOOR_COUNT; floor++) {
      int missing = 0;
      for (int rx = LOAD_REGION_X_FROM; rx <= LOAD_REGION_X_TO; rx++) {
        for (int ry = LOAD_REGION_Y_FROM; ry <= LOAD_REGION_Y_TO; ry++) {
          RawSector s = landscape.sector(floor, rx, ry);
          if (s == null) {
            missing++;
            continue;
          }
          int baseX = (rx - LOAD_REGION_X_FROM) * REGION_SIZE;
          int baseY = (ry - LOAD_REGION_Y_FROM) * REGION_SIZE + floor * FLOOR_HEIGHT;
          for (int x = 0; x < REGION_SIZE; x++) {
            for (int y = 0; y < REGION_SIZE; y++) {
              int bx = baseX + x;
              int by = baseY + y;
              if (bx < 0 || bx >= WIDTH || by < 0 || by >= HEIGHT) {
                continue;
              }
              int idx = x * REGION_SIZE + y; // sector index: x-major, matches server Sector
              m.stampTile(bx, by, s.groundTexture[idx] & 0xFF, s.groundOverlay[idx] & 0xFF,
                  s.roofTexture[idx] & 0xFF, s.horizontalWall[idx] & 0xFF,
                  s.verticalWall[idx] & 0xFF, s.diagonalWalls[idx], doorDefs, tileDefs);
            }
          }
        }
      }
      if (missing > 0) {
        LOG.debug("jag landscape floor {}: {} missing sections (expected on sparse upper floors)",
            floor, missing);
      }
    }
    m.finishLoad(scenery, objectDefs, boundaries, doorDefs);
    return m;
  }

  /**
   * Shared post-decode tail: stamp scenery + boundary locs, seal floor seams, compute connected
   * components.
   */
  private void finishLoad(List<SceneryLocs.Loc> scenery, ObjectDefs objectDefs,
      List<BoundaryLocs.Loc> boundaries, DoorDefs doorDefs) {
    if (scenery != null && objectDefs != null) {
      int stamped = applyScenery(scenery, objectDefs);
      LOG.info("stamped {} scenery blocks into collision map", stamped);
      int clip = applySceneryProjectileClip(scenery, objectDefs);
      LOG.info("stamped {} scenery projectile-clip tiles into collision map", clip);
    }
    if (boundaries != null) {
      int stamped = applyBoundaries(boundaries, doorDefs);
      LOG.info("stamped {} boundary walls into collision map", stamped);
      if (doorDefs != null) {
        int clip = applyBoundaryProjectileClip(boundaries, doorDefs);
        LOG.info("stamped {} boundary projectile-clip tiles into collision map", clip);
      }
    }
    sealFloorSeams();
    computeComponents();
  }

  /**
   * Stamp a wall along every floor seam so walking searches can never step between the flat-encoded
   * floor planes.
   *
   * <p>Why this is needed: each floor's last sector row (sy=912, local y
   * 912..959) bleeds 16 rows past the 944-tile floor stride into the next floor's address space —
   * on the SERVER too (WorldLoader writes them and missing upper-floor sectors leave the stale rows
   * walkable). Pre-fix the map had ~3000 walkable canStep crossings at the floor 1→2 seam and one
   * walking component spanning both floors, letting cluster legs, intra matrices, and the component
   * oracle plan geographic-nonsense "walks" between floors (bottom-row HPA clusters straddle the
   * seam internally).
   *
   * <p>The server would technically accept such a step, but a sane route
   * never needs it — floors are only meant to connect via ladder/stair transports. A static wall
   * row costs nothing on the canStep hot path, unlike a per-call floor-equality check.
   */
  private void sealFloorSeams() {
    for (int floor = 1; floor < FLOOR_COUNT; floor++) {
      int seamY = floor * FLOOR_HEIGHT;
      for (int x = 0; x < WIDTH; x++) {
        or(x, seamY, WALL_NORTH);       // blocks dy=-1 out of the upper plane
        or(x, seamY - 1, WALL_SOUTH);   // blocks dy=+1 out of the lower plane
      }
    }
  }

  /**
   * 8-connected walking flood-fill over the static topology (null overlay). Labels every walkable
   * tile with a 1-based component id; blocked tiles stay 0. Used by {@link #componentId} for the
   * pathfinder's reachability oracle: tiles in different components can never be connected by
   * walking alone, so A* on such pairs can early-exit (Transports may still bridge components — the
   * pathfinder handles that separately).
   */
  private void computeComponents() {
    int label = 0;
    int[] queue = new int[1 << 16]; // grows on demand
    for (int y = 0; y < HEIGHT; y++) {
      for (int x = 0; x < WIDTH; x++) {
        int idx = y * WIDTH + x;
        if (components[idx] != 0) {
          continue;
        }
        if ((flags[idx] & FULL_BLOCK) != 0) {
          continue;
        }
        label++;
        if (label > 0xFFFF) {
          throw new IllegalStateException("too many components for short[] storage");
        }
        int head = 0, tail = 0;
        if (tail >= queue.length) {
          queue = grow(queue);
        }
        queue[tail++] = idx;
        components[idx] = (short) label;
        while (head < tail) {
          int cur = queue[head++];
          int cx = cur % WIDTH;
          int cy = cur / WIDTH;
          for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
              if (dx == 0 && dy == 0) {
                continue;
              }
              int nx = cx + dx, ny = cy + dy;
              if (nx < 0 || nx >= WIDTH || ny < 0 || ny >= HEIGHT) {
                continue;
              }
              int nidx = ny * WIDTH + nx;
              if (components[nidx] != 0) {
                continue;
              }
              if (!canStep(cx, cy, nx, ny, null)) {
                continue;
              }
              components[nidx] = (short) label;
              if (tail >= queue.length) {
                queue = grow(queue);
              }
              queue[tail++] = nidx;
            }
          }
        }
      }
    }
    componentCount = label;
    LOG.info("collision-map: labelled {} walking components", label);
  }

  private static int[] grow(int[] a) {
    int[] b = new int[a.length * 2];
    System.arraycopy(a, 0, b, 0, a.length);
    return b;
  }

  /**
   * Static walking-component id for {@code (x, y)}. 0 = blocked or OOB; ids start at 1. Tiles
   * sharing an id are connected via walking only (no transports). Pathfinder uses this as a
   * reachability oracle to short- circuit unreachable searches before A* floods the map.
   */
  public int componentId(int x, int y) {
    if (!inBounds(x, y)) {
      return 0;
    }
    return components[y * WIDTH + x] & 0xFFFF;
  }

  public int componentCount() {
    return componentCount;
  }

  /**
   * Apply BoundaryLocs entries as wall flags. Mirrors OpenRSC server
   * {@code World.registerGameObject} for {@code BOUNDARY} type: dir 0 = north wall (mirrored as
   * south wall on tile y-1), dir 1 = east wall (mirrored as west wall on x-1), dirs 2/3 = diagonal
   * full-blocks.
   *
   * <p>Door classification logic (replaces the old "skip everything
   * Cmd1≠WalkTo" rule that wrongly let A* route through guild doors, jungle, flamewall, etc.):
   * <ol>
   *   <li>{@link DoorDefs#isGenericFreeDoor} — name+id whitelist for the
   *       server's unconditional-toggle bottom-of-switch arms. SKIPPED
   *       (passable).</li>
   *   <li>Default — wall-stamp. Catches any doorType=1 boundary that
   *       neither has an override nor matches the generic whitelist (i.e.
   *       a door with a script we haven't yet modeled — safer to wall it
   *       than let A* plan through and get stuck clicking).</li>
   * </ol>
   */
  private int applyBoundaries(List<BoundaryLocs.Loc> boundaries, DoorDefs doorDefs) {
    int stamped = 0;
    int skippedDoors = 0;
    for (BoundaryLocs.Loc b : boundaries) {
      int id = b.id();
      // OpenRSC server: only boundaries with DoorType == 1 are collidable
      // (World.java:546-549). DoorType 0 = doorframe-like decoration,
      // 2/3 = projectile-only.
      if (doorDefs.doorType(id) != 1) {
        continue;
      }
      int x = b.pos().x();
      int y = b.pos().y();
      if (!inBounds(x, y)) {
        continue;
      }
      int dir = b.direction();
      // The SpecialDoor override table is omitted in this standalone renderer:
      // fall back to the generic free-door whitelist. Quest/skill-gated doors
      // that aren't generic-free simply stamp as walls (acceptable for a map).
      if (doorDefs.isGenericFreeDoor(id)) {
        skippedDoors++;
        continue;
      }
      switch (b.direction()) {
        case 0 -> {
          or(x, y, WALL_NORTH);
          if (inBounds(x, y - 1)) {
            or(x, y - 1, WALL_SOUTH);
          }
          stamped++;
        }
        case 1 -> {
          or(x, y, WALL_EAST);
          if (inBounds(x - 1, y)) {
            or(x - 1, y, WALL_WEST);
          }
          stamped++;
        }
        case 2 -> {
          or(x, y, FULL_BLOCK_A);
          stamped++;
        }
        case 3 -> {
          or(x, y, FULL_BLOCK_B);
          stamped++;
        }
        default -> { /* unknown direction — ignore */ }
      }
    }
    if (skippedDoors > 0) {
      LOG.info("skipped {} generic free boundary doors (runtime-tracked)", skippedDoors);
    }
    return stamped;
  }

  /**
   * Apply scenery (object) entries. Mirrors OpenRSC server {@code World.registerGameObject} SCENERY
   * branch (World.java:506-543):
   *
   * <ul>
   *   <li>{@code ObjectDef.type == 1} — full block. Every tile in the
   *       footprint gets {@link #FULL_BLOCK_C}.</li>
   *   <li>{@code ObjectDef.type == 2} — single wall. Per-tile cardinal flag
   *       chosen by {@code direction} (0/2/4/6), mirrored onto the adjacent
   *       tile (e.g. dir 0 = WALL_EAST + WALL_WEST on x-1).</li>
   *   <li>Other types (0, 3, 4, …) — non-blocking, skipped.</li>
   * </ul>
   * <p>
   * Footprint width/height swap when {@code direction} is not 0 or 4.
   */
  private int applyScenery(List<SceneryLocs.Loc> scenery, ObjectDefs defs) {
    int stamped = 0;
    int skippedDoors = 0;
    for (SceneryLocs.Loc loc : scenery) {
      ObjectDefs.Entry def = defs.get(loc.id());
      if (def == null) {
        continue;
      }
      int typ = def.typ();
      if (typ != 1 && typ != 2) {
        continue;
      }
      // Door classification mirrors applyBoundaries (see that method's
      // javadoc for the resolution order). For SCENERY: lookup
      // SpecialDoor first; fall back to ObjectDefs's name+id whitelist
      // for generic gates/doors (ids 57/60/64).
      if (typ == 2) {
        if (defs.isGenericFreeDoor(loc.id())) {
          skippedDoors++;
          continue;
        }
      }
      // Server stamps FULL_BLOCK_C on every typ=1 footprint including
      // ladders (openrsc World.java:523-524) — the bot can never walk
      // onto a ladder tile in normal play, only adjacent. Earlier this
      // code skipped climbable typ=1 to avoid "sealing the transport
      // off from A*", but that justification was wrong:
      // LadderTransport.enumerateEntries covers all 8 surrounding tiles
      // (and atObject works from each — see LadderDiagonalEntryIT), so
      // A* can always reach the transport without ever needing to walk
      // onto the ladder itself. Aligning with server here means the
      // pathfinder can't plan paths *through* ladder tiles and the
      // walls overlay correctly dims them.
      int dir = loc.direction();
      int w = def.width();
      int h = def.height();
      if (dir != 0 && dir != 4) {
        int t = w;
        w = h;
        h = t;
      }
      int dx = loc.pos().x();
      int dy = loc.pos().y();
      for (int x = dx; x < dx + w; x++) {
        for (int y = dy; y < dy + h; y++) {
          if (!inBounds(x, y)) {
            continue;
          }
          if (typ == 1) {
            or(x, y, FULL_BLOCK_C);
            stamped++;
          } else { // typ == 2 — direction-keyed wall
            switch (dir) {
              case 0 -> {
                or(x, y, WALL_EAST);
                if (inBounds(x - 1, y)) {
                  or(x - 1, y, WALL_WEST);
                }
                stamped++;
              }
              case 2 -> {
                or(x, y, WALL_SOUTH);
                if (inBounds(x, y + 1)) {
                  or(x, y + 1, WALL_NORTH);
                }
                stamped++;
              }
              case 4 -> {
                or(x, y, WALL_WEST);
                if (inBounds(x + 1, y)) {
                  or(x + 1, y, WALL_EAST);
                }
                stamped++;
              }
              case 6 -> {
                or(x, y, WALL_NORTH);
                if (inBounds(x, y - 1)) {
                  or(x, y - 1, WALL_SOUTH);
                }
                stamped++;
              }
              default -> { /* unknown dir on wall scenery — ignore */ }
            }
          }
        }
      }
    }
    if (skippedDoors > 0) {
      LOG.info("skipped {} openable scenery doors (runtime-tracked)", skippedDoors);
    }
    return stamped;
  }

  /**
   * Stamp {@link #projectileAllowed} for scenery, mirroring server {@code World.registerGameObject}
   * SCENERY (World.java:518-543): for every footprint tile of an object that passes
   * {@code isProjectileClipAllowed}, run {@code handleProjectileClipAllowance}. A full-block
   * ({@code typ==1}) object stamps only its own footprint tiles; a wall ({@code typ==2}) object
   * additionally stamps one direction-keyed neighbour.
   *
   * <p>This is a SEPARATE pass from {@link #applyScenery} (which stamps walking collision) on purpose
   * — projectile clip is independent of the bot's openable-door abstraction (the server stamps it for
   * gates/fences regardless), and keeping the walking-collision stamping untouched guarantees this
   * change can't alter pathfinding.
   */
  private int applySceneryProjectileClip(List<SceneryLocs.Loc> scenery, ObjectDefs defs) {
    int stamped = 0;
    for (SceneryLocs.Loc loc : scenery) {
      ObjectDefs.Entry def = defs.get(loc.id());
      if (def == null) {
        continue;
      }
      int objectType = def.typ(); // server o.getGameObjectDef().getType(): 1=full block, 2=wall
      if (objectType != 1 && objectType != 2) {
        continue;
      }
      // SceneryLocs are loc-type 0 ("o.getType() == 0"): allowed iff 1×1 (raw def dims) and not a
      // tree/chest, OR the name is on the projectile-clip allowlist.
      if (!isSceneryProjectileClipAllowed(def)) {
        continue;
      }
      int dir = loc.direction();
      int w = def.width();
      int h = def.height();
      if (dir != 0 && dir != 4) {
        int t = w;
        w = h;
        h = t;
      }
      int dx = loc.pos().x();
      int dy = loc.pos().y();
      for (int x = dx; x < dx + w; x++) {
        for (int y = dy; y < dy + h; y++) {
          // server handleProjectileClipAllowance(x, y, dir, type=0, objectType, doorType=-1)
          stamped += setProjectileAllowed(x, y) ? 1 : 0;
          // Full-block scenery (objectType==1) stamps only the footprint tile (early return on
          // server: type==0 && objectType==1); wall scenery (objectType==2) also stamps one neighbour.
          if (objectType == 1) {
            continue;
          }
          switch (dir) {
            case 0 -> stamped += setProjectileAllowed(x - 1, y) ? 1 : 0;
            case 2 -> stamped += setProjectileAllowed(x, y + 1) ? 1 : 0;
            case 4 -> stamped += setProjectileAllowed(x + 1, y) ? 1 : 0;
            case 6 -> stamped += setProjectileAllowed(x, y - 1) ? 1 : 0;
            default -> { /* server only special-cases 0/2/4/6 */ }
          }
        }
      }
    }
    return stamped;
  }

  /**
   * Stamp {@link #projectileAllowed} for boundaries (doors/walls), mirroring server
   * {@code World.registerGameObject} BOUNDARY (World.java:546-553): only doorType-1 boundaries whose
   * name is on the allowlist, and then {@code handleProjectileClipAllowance(x, y, dir, type=1, -1,
   * doorType=1)} — which stamps {@code (x, y)} plus one neighbour, but only for boundary {@code
   * dir==0} (the server's dir cases are 0/2/4/6 and boundary dirs are 0/1/2/3, so 1/2/3 stamp the
   * base tile alone). Faithful to that quirk; the parity test pins it.
   */
  private int applyBoundaryProjectileClip(List<BoundaryLocs.Loc> boundaries, DoorDefs doorDefs) {
    int stamped = 0;
    for (BoundaryLocs.Loc b : boundaries) {
      int id = b.id();
      if (doorDefs.doorType(id) != 1) { // server returns early for non-type-1 boundaries
        continue;
      }
      if (!isNameProjectileClipAllowed(doorDefs.name(id))) { // boundary is loc-type 1 → name-only test
        continue;
      }
      int x = b.pos().x();
      int y = b.pos().y();
      int dir = b.direction();
      stamped += setProjectileAllowed(x, y) ? 1 : 0;
      // type==1 && doorType==1 → does NOT early-return, so the dir-keyed neighbour is also stamped.
      switch (dir) {
        case 0 -> stamped += setProjectileAllowed(x - 1, y) ? 1 : 0;
        case 2 -> stamped += setProjectileAllowed(x, y + 1) ? 1 : 0;
        case 4 -> stamped += setProjectileAllowed(x + 1, y) ? 1 : 0;
        case 6 -> stamped += setProjectileAllowed(x, y - 1) ? 1 : 0;
        default -> { /* boundary dirs 1/2/3 stamp the base tile only, per the server */ }
      }
    }
    return stamped;
  }

  /** Server {@code isProjectileClipAllowed} for a loc-type-0 scenery object (reads its def). */
  private static boolean isSceneryProjectileClipAllowed(ObjectDefs.Entry def) {
    String name = def.name() == null ? "" : def.name();
    if (!name.equalsIgnoreCase("tree")
        && def.width() == 1 && def.height() == 1
        && !name.equalsIgnoreCase("chest")) {
      return true;
    }
    return isNameProjectileClipAllowed(name);
  }

  private static boolean isNameProjectileClipAllowed(String name) {
    return name != null && PROJECTILE_CLIP_ALLOWED_NAMES.contains(name.toLowerCase());
  }

  /** Set {@link #projectileAllowed} at a tile; returns true if it flipped a fresh tile (for stats). */
  private boolean setProjectileAllowed(int x, int y) {
    if (!inBounds(x, y)) {
      return false;
    }
    int idx = y * WIDTH + x;
    if (projectileAllowed[idx]) {
      return false;
    }
    projectileAllowed[idx] = true;
    return true;
  }

  /** Server {@code TileValue.projectileAllowed} for {@code (x, y)}; false out of bounds. */
  public boolean projectileAllowed(int x, int y) {
    return inBounds(x, y) && projectileAllowed[y * WIDTH + x];
  }

  private void loadSection(InputStream is, int regionX, int regionY, int floor,
      DoorDefs doorDefs, TileDefs tileDefs) throws IOException {
    DataInputStream in = new DataInputStream(is);
    // The archive stores rows-by-cols at (x, y) within the region; absolute
    // tile = ((rx - START_RX) * 48 + x, (ry - START_RY) * 48 + y + floor*944).
    int baseX = (regionX - LOAD_REGION_X_FROM) * REGION_SIZE;
    int baseY = (regionY - LOAD_REGION_Y_FROM) * REGION_SIZE + floor * FLOOR_HEIGHT;
    for (int x = 0; x < REGION_SIZE; x++) {
      for (int y = 0; y < REGION_SIZE; y++) {
        in.readByte(); // groundElevation
        int texture = in.readByte() & 0xFF;
        int groundOverlay = in.readByte() & 0xFF;
        int roofTex = in.readByte() & 0xFF;
        int horizontalWall = in.readByte() & 0xFF;
        int verticalWall = in.readByte() & 0xFF;
        int diagonalWalls = in.readInt();

        int bx = baseX + x;
        int by = baseY + y;
        if (bx < 0 || bx >= WIDTH || by < 0 || by >= HEIGHT) {
          continue;
        }

        stampTile(bx, by, texture, groundOverlay, roofTex,
            horizontalWall, verticalWall, diagonalWalls, doorDefs, tileDefs);
      }
    }
  }

  /**
   * Stamp one tile's raw landscape record (texture / overlay / roof / walls) into {@link #flags} +
   * the render arrays. Shared by the {@code .orsc} loader ({@link #loadSection}) and the JAG loader
   * ({@link #loadFromJag}); both decode the same fields, only the container differs. Mirrors the
   * server's {@code WorldLoader.loadSection} per-tile body, with the bot's generic-free-door
   * exception ({@link #isLandscapeWallFree}) so A* doesn't treat openable doors as walls.
   */
  private void stampTile(int bx, int by, int texture, int groundOverlay, int roofTex,
      int horizontalWall, int verticalWall, int diagonalWalls,
      DoorDefs doorDefs, TileDefs tileDefs) {
    if (groundOverlay == 250) {
      groundOverlay = 2;
    }
    int idx = by * WIDTH + bx;
    // Section is present for this tile — clear the void-default
    // FULL_BLOCK_C bit before stamping. Mirrors server
    // WorldLoader.loadSection setting traversalMask = 0 at the
    // top of its per-tile loop.
    flags[idx] = 0;
    this.groundTexture[idx] = (byte) texture;
    this.groundOverlay[idx] = (byte) groundOverlay;
    this.roofTexture[idx] = (byte) roofTex;
    if (groundOverlay > 0 && tileDefs.objectType(groundOverlay - 1) != 0) {
      or(bx, by, FULL_BLOCK_C);
    }
    // Door classification mirrors applyBoundaries — SpecialDoor
    // override first, then DoorDefs's name+id whitelist for
    // generic free doors. Landscape-archive walls are wall-
    // stamped unless they're a known toggle-anyone boundary.
    if (verticalWall > 0
        && doorDefs.unknown(verticalWall - 1) == 0
        && doorDefs.doorType(verticalWall - 1) != 0
        && !isLandscapeWallFree(doorDefs, verticalWall - 1, bx, by, 0)) {
      or(bx, by, WALL_NORTH);
      if (by - 1 >= 0) {
        or(bx, by - 1, WALL_SOUTH);
      }
    }
    if (horizontalWall > 0
        && doorDefs.unknown(horizontalWall - 1) == 0
        && doorDefs.doorType(horizontalWall - 1) != 0
        && !isLandscapeWallFree(doorDefs, horizontalWall - 1, bx, by, 1)) {
      or(bx, by, WALL_EAST);
      if (bx - 1 >= 0) {
        or(bx - 1, by, WALL_WEST);
      }
    }
    int dw = diagonalWalls & 0xFFFF; // plutonium truncates to int16
    if (dw > 0 && dw < 12000
        && doorDefs.unknown(dw - 1) == 0
        && doorDefs.doorType(dw - 1) != 0
        && !isLandscapeWallFree(doorDefs, dw - 1, bx, by, 2)) {
      or(bx, by, FULL_BLOCK_B);
    }
    if (dw > 12000 && dw < 24000
        && doorDefs.unknown(dw - 12001) == 0
        && doorDefs.doorType(dw - 12001) != 0
        && !isLandscapeWallFree(doorDefs, dw - 12001, bx, by, 3)) {
      or(bx, by, FULL_BLOCK_A);
    }
  }

  private void or(int x, int y, int mask) {
    flags[y * WIDTH + x] |= (byte) mask;
  }

  /**
   * Landscape-wall door classification (per-tile-wall variant of the BoundaryLocs check). True iff
   * the boundary id at (bx, by) with the given direction is "anyone toggles" — same resolution
   * order as {@link #applyBoundaries}: SpecialDoor override first, then DoorDefs name+id
   * whitelist.
   */
  private static boolean isLandscapeWallFree(DoorDefs doorDefs, int id, int bx, int by, int dir) {
    return doorDefs.isGenericFreeDoor(id);
  }


  public boolean inBounds(int x, int y) {
    return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT;
  }

  /**
   * Raw flag byte for the tile. {@link #FULL_BLOCK} if out of bounds.
   */
  public int flags(int x, int y) {
    if (!inBounds(x, y)) {
      return FULL_BLOCK;
    }
    return flags[y * WIDTH + x] & 0xFF;
  }

  /**
   * Static + overlay flag byte for the tile.
   */
  public int flags(int x, int y, WallOverlay overlay) {
    int f = flags(x, y);
    if (overlay != null) {
      f |= overlay.flags(x, y);
    }
    return f;
  }

  /**
   * <b>Static</b> "is {@code (x,y)} unwalkable" — the base-map full-block test with no dynamic
   * overlay. This is the deliberate static-topology primitive for the pathfinder's stable layers
   * (component graph, HPA cluster building, transport entry enumeration), where dynamic walls only
   * <em>add</em> blockage and a static-clear tile is reachable once the walker opens the door en
   * route. For the bot's <em>current</em> blockage (static + dynamic / closed doors) call
   * {@link #fullyBlocked(int, int, WallOverlay)} with the live overlay.
   */
  public boolean fullyBlocked(int x, int y) {
    return (flags(x, y) & FULL_BLOCK) != 0;
  }

  public boolean fullyBlocked(int x, int y, WallOverlay overlay) {
    return (flags(x, y, overlay) & FULL_BLOCK) != 0;
  }

  /**
   * Can the player step from {@code (fx, fy)} → {@code (tx, ty)}? Used by the pathfinder.
   * {@code (tx, ty)} must be a 4- or 8-neighbour of {@code (fx, fy)}.
   *
   * <p>Literal port of the server's {@code PathValidation.checkAdjacent}
   * (mob/player blocking omitted — runtime concerns, not map topology). Cardinal arms keep the
   * original fast path, which a full-map differential probe showed is exactly equivalent to the
   * server rules. Diagonal arms run the server's complete rule sequence including
   * {@code checkDiagonalPassThroughCollisions} — the previous "one flanking cardinal must be
   * steppable" approximation allowed 184 map-wide diagonal steps the server refuses (corner-clip
   * cases), each a silent server-side walk stall. See {@code CollisionMapServerParityTest}.
   *
   * <p><b>Static</b> form: no wall overlay and no mob occupancy — the base-map step test. Use it for
   * the pathfinder's stable static graph; for the bot's current topology (dynamic walls / closed
   * doors) call {@link #canStep(int, int, int, int, WallOverlay)} with the live overlay, or just use
   * {@code Bot.canStep}, which always passes it. (The script-facing {@code Bot.canStep} is never
   * static — only this low-level map primitive is.)
   */
  public boolean canStep(int fx, int fy, int tx, int ty) {
    return canStep(fx, fy, tx, ty, null, null);
  }

  public boolean canStep(int fx, int fy, int tx, int ty, WallOverlay overlay) {
    return canStep(fx, fy, tx, ty, overlay, null);
  }

  /**
   * Transient per-tick tile occupancy (blocking NPCs). Mirrors the {@code isMobBlocking}
   * contribution inside the server's {@code checkBlocking}: consulted at the step destination and,
   * for diagonals, at the two flanking tiles — never at the source tile (the server exempts the
   * mover's own tile).
   */
  @FunctionalInterface
  public interface TileOccupancy {

    boolean blockedAt(int x, int y);
  }

  /**
   * {@link #canStep} with live mob occupancy injected at exactly the tiles where the server's
   * {@code checkAdjacent} consults {@code isMobBlocking} (every {@code checkBlocking} call site
   * except the mover's own tile; the diagonal pass-through reads raw tile masks and stays
   * mob-free). Wall-on-one-flank + mob-on-the-other combinations block a diagonal exactly as on the
   * server.
   */
  public boolean canStep(int fx, int fy, int tx, int ty, WallOverlay overlay, TileOccupancy mob) {
    if (!inBounds(tx, ty) || fullyBlocked(tx, ty, overlay)) {
      return false;
    }
    int dx = tx - fx, dy = ty - fy;
    int from = flags(fx, fy, overlay);
    // OpenRSC coordinate convention (per server PathValidation.checkAdjacentDistance):
    //   - decreasing X (dx=-1) corresponds to "east"  — checks WALL_EAST on source
    //   - increasing X (dx=+1) corresponds to "west"  — checks WALL_WEST on source
    //   - decreasing Y (dy=-1) corresponds to "north" — checks WALL_NORTH on source
    //   - increasing Y (dy=+1) corresponds to "south" — checks WALL_SOUTH on source
    //
    // Branch tree is on (dx, dy) packed into a small int. Avoid String
    // concat in switch — this method is on the A* hot path; the old
    // `switch (dx + "," + dy)` allocated a String every call.
    if (dy == 0 || dx == 0) {
      // Cardinal: mob contribution reduces to "destination occupied"
      // (server: newX/newYBlocked at dest && startY == coords[1]).
      if (mob != null && mob.blockedAt(tx, ty)) {
        return false;
      }
      if (dy == 0) {
        if (dx == 1) {
          return (from & WALL_WEST) == 0;
        }
        if (dx == -1) {
          return (from & WALL_EAST) == 0;
        }
        return false;
      }
      if (dy == 1) {
        return (from & WALL_SOUTH) == 0;
      }
      if (dy == -1) {
        return (from & WALL_NORTH) == 0;
      }
      return false;
    }
    if (dx > 1 || dx < -1 || dy > 1 || dy < -1) {
      return false;
    }
    return canStepDiagonal(fx, fy, tx, ty, from, overlay, mob);
  }

  /**
   * Tick-denominated cost of an already-validated step (10 units = one server tick). Cardinals cost
   * 10. Diagonals price the server's packet-time interpolation ({@code Path.addStep}, WalkRequest
   * path):
   * <ul>
   *   <li>both flanking cardinal steps walkable → the diagonal rides
   *       through as one waypoint → 10;</li>
   *   <li>exactly ONE flank walkable → the server zigzags through that
   *       flank: two waypoints, two ticks → 20;</li>
   *   <li>neither flank walkable → addStep's fallback emits the diagonal
   *       directly (one waypoint) and the per-tick {@code checkAdjacent}
   *       — which the caller already passed via {@link #canStep} —
   *       accepts it → 10.</li>
   * </ul>
   * The server moves one waypoint per tick regardless of diagonality, so
   * WITHOUT this rule a planner counting every step as one tick
   * systematically underprices corner-hugging diagonals past trees,
   * rocks and fences. Caller must have verified {@code canStep} for the
   * step itself; this only prices it.
   */
  public int stepCost10(int fx, int fy, int tx, int ty, WallOverlay overlay, TileOccupancy mob) {
    int dx = tx - fx, dy = ty - fy;
    if (dx == 0 || dy == 0) {
      return 10;
    }
    boolean flankX = canStep(fx, fy, tx, fy, overlay, mob);
    boolean flankY = canStep(fx, fy, fx, ty, overlay, mob);
    return flankX == flankY ? 10 : 20;
  }

  /** {@link #diagonalIntermediate} sentinel: the bot rides straight to the destination, no zigzag. */
  public static final long NO_INTERMEDIATE = Long.MIN_VALUE;

  /**
   * Predict the cardinal tile the server inserts when it ZIGZAGS a diagonal step at packet time
   * ({@code Path.addStep}), or {@link #NO_INTERMEDIATE} when the bot rides straight to {@code (tx,ty)}
   * in one tick. This is the geometric companion to {@link #stepCost10}: the same one-flank-open rule
   * that prices the zigzag at 20 also tells you WHICH tile the bot transits — and that tile can sit
   * inside a hostile's aggro range even when neither diagonal endpoint does, which is how a route that
   * looks clear at the planned waypoints still draws aggro. The walker expands its emitted chunk
   * through this before testing for grazes so the danger check sees the tile the bot truly occupies.
   *
   * <p>Mirrors {@code Path.addStep} for one {@code |dx|==|dy|==1} step, with
   * {@code flankX = canStep(A→horizontal neighbour)}, {@code flankY = canStep(A→vertical neighbour)},
   * {@code diag = canStep(A→B)}:
   * <ul>
   *   <li>both flanks open + straight diagonal clear → ride through (no intermediate);</li>
   *   <li>both flanks open + a diagonal wall between (!diag) → zigzag, horizontal-first if that
   *       intermediate can then reach B, else vertical-first. (Unreachable for a {@link #canStep}-valid
   *       step — {@code canStep(A→B)} is false here, so the planner never emits such a diagonal — but
   *       mirrored for completeness.);</li>
   *   <li>exactly one flank open → the server steps that cardinal first → that flank IS the
   *       intermediate (the common corner-hug case, priced 20);</li>
   *   <li>neither flank open → {@code addStep}'s fallback emits the diagonal directly → no
   *       intermediate.</li>
   * </ul>
   * Returns the intermediate packed as {@code (x << 32) | (z & 0xFFFFFFFFL)}.
   */
  public long diagonalIntermediate(int fx, int fy, int tx, int ty, WallOverlay overlay,
      TileOccupancy mob) {
    int dx = tx - fx, dy = ty - fy;
    if (dx == 0 || dy == 0) {
      return NO_INTERMEDIATE; // cardinals never zigzag
    }
    boolean flankX = canStep(fx, fy, tx, fy, overlay, mob);
    boolean flankY = canStep(fx, fy, fx, ty, overlay, mob);
    if (flankX && flankY) {
      if (canStep(fx, fy, tx, ty, overlay, mob)) {
        return NO_INTERMEDIATE; // straight diagonal rides through in one tick
      }
      if (canStep(tx, fy, tx, ty, overlay, mob)) {
        return ((long) tx << 32) | (fy & 0xFFFFFFFFL); // horizontal-first
      }
      if (canStep(fx, ty, tx, ty, overlay, mob)) {
        return ((long) fx << 32) | (ty & 0xFFFFFFFFL); // vertical-first
      }
      return NO_INTERMEDIATE; // server truncates the path; bot won't transit either flank
    }
    if (flankX) {
      return ((long) tx << 32) | (fy & 0xFFFFFFFFL); // only horizontal flank open
    }
    if (flankY) {
      return ((long) fx << 32) | (ty & 0xFFFFFFFFL); // only vertical flank open
    }
    return NO_INTERMEDIATE; // neither flank: addStep emits the diagonal directly
  }

  /**
   * True iff the destination-style blocking test passes for {@code (x, y)}: the given wall bit is
   * clear AND the tile is not fully blocked. Mirrors server {@code PathValidation.isBlocking} with
   * {@code isCurrentTile=false} (positive bit ⇒ all three FULL_BLOCK variants block).
   */
  private boolean destBlocked(int x, int y, int wallBits, WallOverlay overlay) {
    int v = flags(x, y, overlay);
    return (v & wallBits) != 0 || (v & FULL_BLOCK) != 0;
  }

  private static boolean occupied(TileOccupancy mob, int x, int y) {
    return mob != null && mob.blockedAt(x, y);
  }

  /**
   * Diagonal arm of {@link #canStep} — the server's full {@code checkAdjacent} rule sequence for
   * {@code |dx|==|dy|==1}. {@code from} is the already-fetched source-tile flag byte.
   */
  private boolean canStepDiagonal(int fx, int fy, int tx, int ty, int from, WallOverlay overlay,
      TileOccupancy mob) {
    // First pass: walls on the source tile (full blocks ignored on the
    // current tile) and on the two flanking tiles (full blocks count;
    // mob occupancy contributes exactly like the server's
    // isMobBlocking inside checkBlocking).
    boolean myX, newX;
    if (tx < fx) { // moving "east" (decreasing x)
      myX = (from & WALL_EAST) != 0;
      newX = destBlocked(fx - 1, fy, WALL_WEST, overlay) || occupied(mob, fx - 1, fy);
    } else {       // moving "west"
      myX = (from & WALL_WEST) != 0;
      newX = destBlocked(fx + 1, fy, WALL_EAST, overlay) || occupied(mob, fx + 1, fy);
    }
    boolean myY, newY;
    if (ty < fy) { // moving "north" (decreasing y)
      myY = (from & WALL_NORTH) != 0;
      newY = destBlocked(fx, fy - 1, WALL_SOUTH, overlay) || occupied(mob, fx, fy - 1);
    } else {       // moving "south"
      myY = (from & WALL_SOUTH) != 0;
      newY = destBlocked(fx, fy + 1, WALL_NORTH, overlay) || occupied(mob, fx, fy + 1);
    }
    if (myX && myY) {
      return false;
    }
    if (newX && newY) {
      return false;
    }
    if ((myX && newX) || (myY && newY)) {
      return false;
    }
    // Second pass: re-test both axes' walls on the destination tile.
    // (Server recomputes newX/newY at coords == dest for diagonals.)
    newX = destBlocked(tx, ty, tx > fx ? WALL_EAST : WALL_WEST, overlay) || occupied(mob, tx, ty);
    newY = destBlocked(tx, ty, ty > fy ? WALL_NORTH : WALL_SOUTH, overlay) || occupied(mob, tx, ty);
    if (newX && newY) {
      return false;
    }
    if (myX && newX) {
      return false;
    }
    if (myY && newY) {
      return false;
    }
    // Destination corner wall pair (PathValidation.java:599-615): the
    // dest tile must not carry the two walls facing the approach corner.
    int cornerBits = (tx > fx ? WALL_EAST : WALL_WEST)
                     | (ty > fy ? WALL_NORTH : WALL_SOUTH);
    if (destBlocked(tx, ty, cornerBits, overlay)) {
      return false;
    }
    return !diagonalPassThroughBlocked(fx, fy, tx, ty, overlay);
  }

  /**
   * Port of server {@code PathValidation.checkDiagonalPassThroughCollisions}: when a flanking tile
   * is full-blocked (diagonal wall or scenery), specific wall flags on the flanking/corner tiles
   * block the diagonal squeeze. Returns {@code true} when the step is blocked.
   *
   * <p>Faithfully reproduces the server's per-direction tile reads —
   * including the "northwest" branch reading {@code (x+1, y+1)} (PathValidation.java:389), which is
   * NOT the corner tile. Quirk or not, the server is the contract; the full-map parity test pins
   * it.
   */
  private boolean diagonalPassThroughBlocked(int x, int y, int xn, int yn, WallOverlay overlay) {
    if (xn == x - 1 && yn == y - 1) { // server "northeast"
      int m = flags(x - 1, y, overlay);
      if ((m & (FULL_BLOCK_A | FULL_BLOCK_C)) != 0) {
        if ((flags(x, y - 1, overlay) & WALL_EAST) != 0) {
          return true;
        }
        if ((flags(x - 1, y - 1, overlay) & WALL_WEST) != 0) {
          return true;
        }
      }
      m = flags(x, y - 1, overlay);
      if ((m & (FULL_BLOCK_A | FULL_BLOCK_C)) != 0) {
        if ((flags(x - 1, y, overlay) & WALL_NORTH) != 0) {
          return true;
        }
        return (flags(x - 1, y - 1, overlay) & WALL_SOUTH) != 0;
      }
      return false;
    }
    if (xn == x + 1 && yn == y - 1) { // server "northwest"
      int m = flags(x + 1, y, overlay);
      if ((m & (FULL_BLOCK_B | FULL_BLOCK_C)) != 0) {
        if ((flags(x, y - 1, overlay) & WALL_WEST) != 0) {
          return true;
        }
        if ((flags(x + 1, y + 1, overlay) & WALL_EAST) != 0) {
          return true; // sic: server reads (x+1, y+1)
        }
      }
      m = flags(x, y - 1, overlay);
      if ((m & (FULL_BLOCK_B | FULL_BLOCK_C)) != 0) {
        if ((flags(x + 1, y, overlay) & WALL_NORTH) != 0) {
          return true;
        }
        return (flags(x + 1, y - 1, overlay) & WALL_SOUTH) != 0;
      }
      return false;
    }
    if (xn == x - 1 && yn == y + 1) { // server "southeast"
      int m = flags(x - 1, y, overlay);
      if ((m & (FULL_BLOCK_B | FULL_BLOCK_C)) != 0) {
        if ((flags(x, y + 1, overlay) & WALL_EAST) != 0) {
          return true;
        }
        if ((flags(x - 1, y + 1, overlay) & WALL_WEST) != 0) {
          return true;
        }
      }
      m = flags(x, y + 1, overlay);
      if ((m & (FULL_BLOCK_B | FULL_BLOCK_C)) != 0) {
        if ((flags(x - 1, y, overlay) & WALL_SOUTH) != 0) {
          return true;
        }
        return (flags(x - 1, y + 1, overlay) & WALL_NORTH) != 0;
      }
      return false;
    }
    if (xn == x + 1 && yn == y + 1) { // server "southwest"
      int m = flags(x + 1, y, overlay);
      if ((m & (FULL_BLOCK_A | FULL_BLOCK_C)) != 0) {
        if ((flags(x, y + 1, overlay) & WALL_WEST) != 0) {
          return true;
        }
        if ((flags(x + 1, y + 1, overlay) & WALL_EAST) != 0) {
          return true;
        }
      }
      m = flags(x, y + 1, overlay);
      if ((m & (FULL_BLOCK_A | FULL_BLOCK_C)) != 0) {
        if ((flags(x + 1, y, overlay) & WALL_SOUTH) != 0) {
          return true;
        }
        return (flags(x + 1, y + 1, overlay) & WALL_NORTH) != 0;
      }
      return false;
    }
    return false;
  }

  /**
   * Can the player engage (talk/attack/click) a target one tile away at {@code (tx, ty)} from
   * {@code (fx, fy)}? This is the <b>object-reach</b> adjacency (≈ the server's {@code Mob.canReach}):
   * like {@link #canStep} but does NOT reject targets that sit on a fully-blocked tile — fishing
   * spots, trees, ladders, anvils, ranges, furnaces all sit on impassable scenery the player walks UP
   * TO, not ONTO.
   *
   * <p><b>Not line-of-sight.</b> It checks only the source/target walls, NOT a diagonal step's
   * corner-clip, so it is <em>not</em> a faithful {@code PathValidation.checkAdjacentDistance} and must
   * not be used for LOS gating ({@link #checkPath} uses {@link #canStep} for that — see its note).
   *
   * <p>Skipping the fully-blocked-target check is critical: gating engagement on
   * {@code canStep} forces {@code BotImpl.tryEngageWalk} to pathfind to an impassable destination,
   * which exhausts the 1M-node cap and burns the 50ms watchdog budget (see
   * [[pathfinder_impassable_destination]]).
   */
  public boolean canEngage(int fx, int fy, int tx, int ty, WallOverlay overlay) {
    if (!inBounds(tx, ty)) {
      return false;
    }
    int dx = tx - fx, dy = ty - fy;
    if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
      return false;
    }
    if (dx == 0 && dy == 0) {
      return true;
    }
    int from = flags(fx, fy, overlay);
    // Int branches, not `switch (dx + "," + dy)` — this backs Bot.canReach,
    // which per-tick pickers call across many candidates; the String
    // concat allocated on every call (same fix as canStep's hot path).
    if (dy == 0) {
      if (dx == 1) {
        return (from & WALL_WEST) == 0;
      }
      return (from & WALL_EAST) == 0; // dx == -1
    }
    if (dx == 0) {
      if (dy == 1) {
        return (from & WALL_SOUTH) == 0;
      }
      return (from & WALL_NORTH) == 0; // dy == -1
    }
    int to = flags(tx, ty, overlay);
    if (dx == 1 && dy == -1) {
      return (from & (WALL_WEST | WALL_NORTH)) == 0 && (to & (WALL_SOUTH | WALL_EAST)) == 0;
    }
    if (dx == 1) { // dy == 1
      return (from & (WALL_WEST | WALL_SOUTH)) == 0 && (to & (WALL_NORTH | WALL_EAST)) == 0;
    }
    if (dy == -1) { // dx == -1
      return (from & (WALL_EAST | WALL_NORTH)) == 0 && (to & (WALL_SOUTH | WALL_WEST)) == 0;
    }
    // dx == -1, dy == 1
    return (from & (WALL_EAST | WALL_SOUTH)) == 0 && (to & (WALL_NORTH | WALL_WEST)) == 0;
  }

  /**
   * Straight-line, wall-aware line of sight between two tiles — the bot mirror of the server's
   * {@code PathValidation.checkPath}, which gates non-walking interactions that need a clear line:
   * trading, ranged, magic, dueling. Steps from {@code (fromX, fromY)} toward {@code (toX, toY)} one
   * tile at a time (diagonal-toward, exactly the server's fill loop) and rejects the first step
   * {@link #checkProjectileStep} blocks. Returns true for a zero-length line (same tile).
   *
   * <p><b>This is the PROJECTILE check, not the walking check.</b> The server uses a different
   * per-step gate here than for movement: {@code checkAdjacentDistance} (via {@code
   * checkBlockingDistance}), NOT {@code checkAdjacent}. The two diverge in two ways that
   * {@link #checkProjectileStep} reproduces and {@link #canStep} (walking) does not:
   * <ul>
   *   <li>a {@link #projectileAllowed} tile (1×1 scenery like an anvil, signs, railings, gates,
   *       fences, tables, doorType-1 boundaries…) is <em>clear</em> for line of sight even though it
   *       blocks walking — this is why a trade across an anvil works in both directions on the
   *       server but {@code canStep} would falsely wall one direction;</li>
   *   <li>the diagonal rule differs — {@code checkAdjacentDistance} uses the {@code bit==-2}
   *       diagonal-wall test and does <em>not</em> run {@code checkDiagonalPassThroughCollisions}.</li>
   * </ul>
   * Using {@code canStep} here (as this method once did) reported lines blocked that the server
   * allows — a trader stopping a tile short of a furnace/anvil operator, the request bouncing forever.
   *
   * <p>Pass the door-honest <b>LOS overlay</b> (not the walker overlay): the server treats a closed
   * door as a wall here, so a check that read the walker overlay — which omits openable doors so A*
   * can route through them — would wrongly report a clear line through a shut door.
   */
  public boolean checkPath(int fromX, int fromY, int toX, int toY, WallOverlay overlay) {
    int curX = fromX, curY = fromY;
    int diffX = toX - fromX;
    int diffY = toY - fromY;
    int max = Math.max(Math.abs(diffX), Math.abs(diffY));
    for (int i = 0; i < max; i++) {
      if (diffX > 0) {
        diffX--;
      } else if (diffX < 0) {
        diffX++;
      }
      if (diffY > 0) {
        diffY--;
      } else if (diffY < 0) {
        diffY++;
      }
      int nextX = toX - diffX;
      int nextY = toY - diffY;
      if (!checkProjectileStep(curX, curY, nextX, nextY, overlay)) {
        return false;
      }
      curX = nextX;
      curY = nextY;
    }
    return true;
  }

  /**
   * Projectile/line-of-sight adjacency — literal port of server
   * {@code PathValidation.checkAdjacentDistance(world, cur, next, ignoreProjectileAllowed=false,
   * wantDiagCheck=true)}, the per-step gate {@code PathValidation.checkPath} uses for trade / ranged /
   * magic / duel. Distinct from {@link #canStep} (the walking gate, server {@code checkAdjacent}): it
   * honours {@link #projectileAllowed} and uses the projectile diagonal rule. {@code (startX,startY)}
   * and {@code (destX,destY)} must be 4- or 8-adjacent.
   */
  private boolean checkProjectileStep(int startX, int startY, int destX, int destY,
      WallOverlay overlay) {
    int cx = startX, cy = startY; // server `coords`
    boolean myX = false, myY = false, newX = false, newY = false;
    if (startX > destX) {
      myX = projBlocked(startX, startY, WALL_EAST, true, overlay);
      newX = projBlocked(startX - 1, startY, WALL_WEST, false, overlay);
      cx = startX - 1;
    } else if (startX < destX) {
      myX = projBlocked(startX, startY, WALL_WEST, true, overlay);
      newX = projBlocked(startX + 1, startY, WALL_EAST, false, overlay);
      cx = startX + 1;
    }
    if (startY > destY) {
      myY = projBlocked(startX, startY, WALL_NORTH, true, overlay);
      newY = projBlocked(startX, startY - 1, WALL_SOUTH, false, overlay);
      cy = startY - 1;
    } else if (startY < destY) {
      myY = projBlocked(startX, startY, WALL_SOUTH, true, overlay);
      newY = projBlocked(startX, startY + 1, WALL_NORTH, false, overlay);
      cy = startY + 1;
    }
    if (myX && myY) {
      return false;
    }
    if (myX && startY == destY) {
      return false;
    }
    if (myY && startX == destX) {
      return false;
    }
    if (newX && newY) {
      return false;
    }
    // Re-test new X/Y walls at the destination-side tile (server recomputes at `coords`).
    if (cx > startX) {
      newX = projBlocked(cx, cy, WALL_EAST, false, overlay);
    } else if (cx < startX) {
      newX = projBlocked(cx, cy, WALL_WEST, false, overlay);
    }
    if (cy > startY) {
      newY = projBlocked(cx, cy, WALL_NORTH, false, overlay);
    } else if (cy < startY) {
      newY = projBlocked(cx, cy, WALL_SOUTH, false, overlay);
    }
    if (newX && newY) {
      return false;
    }
    if (newX && startY == cy) {
      return false;
    }
    if (newY && startX == cx) {
      return false;
    }
    if (myX && newX) {
      return false;
    }
    if (myY && newY) {
      return false;
    }
    // Diagonal corner-wall pair on the destination tile (wantDiagCheck=true → the wall-pair bit).
    boolean diagonalBlocked = false;
    if (startX + 1 == destX && startY + 1 == destY) {
      diagonalBlocked = projBlocked(startX + 1, startY + 1, WALL_NORTH | WALL_EAST, false, overlay);
    } else if (startX + 1 == destX && startY - 1 == destY) {
      diagonalBlocked = projBlocked(startX + 1, startY - 1, WALL_SOUTH | WALL_EAST, false, overlay);
    } else if (startX - 1 == destX && startY + 1 == destY) {
      diagonalBlocked = projBlocked(startX - 1, startY + 1, WALL_NORTH | WALL_WEST, false, overlay);
    } else if (startX - 1 == destX && startY - 1 == destY) {
      diagonalBlocked = projBlocked(startX - 1, startY - 1, WALL_SOUTH | WALL_WEST, false, overlay);
    }
    if (diagonalBlocked) {
      return false;
    }
    // Diagonal-wall-blocks-diagonal-movement: bit -2 = ONLY the diagonal full-blocks (A/B), full
    // block C ignored (server PathValidation.java:232-245).
    int xDiff = destX - startX, yDiff = destY - startY;
    if (Math.abs(xDiff) == 1 && Math.abs(yDiff) == 1) {
      if (projBlocked(startX + xDiff, startY, -2, false, overlay)
          || projBlocked(startX, startY + yDiff, -2, false, overlay)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Server {@code checkBlockingDistance(world, x, y, bit, isCurrentTile, ignoreProjectileAllowed =
   * false)}: a {@link #projectileAllowed} tile never blocks; otherwise apply {@link #isBlockingMask}
   * to the static+overlay flags.
   */
  private boolean projBlocked(int x, int y, int bit, boolean isCurrentTile, WallOverlay overlay) {
    if (projectileAllowed(x, y)) {
      return false;
    }
    return isBlockingMask(flags(x, y, overlay), bit, isCurrentTile);
  }

  /**
   * Server {@code PathValidation.isBlocking}. {@code bit > -1} checks the given wall bit(s); a non-
   * current tile is also blocked by {@code FULL_BLOCK_A}/{@code _B} always and by {@code _C} unless
   * {@code bit <= -2} (the diagonal-only probe).
   */
  private static boolean isBlockingMask(int objectValue, int bit, boolean isCurrentTile) {
    if (bit > -1 && (objectValue & bit) != 0) {
      return true;
    }
    if (!isCurrentTile && (objectValue & FULL_BLOCK_A) != 0) {
      return true;
    }
    if (!isCurrentTile && (objectValue & FULL_BLOCK_B) != 0) {
      return true;
    }
    return bit > -2 && !isCurrentTile && (objectValue & FULL_BLOCK_C) != 0;
  }

  /**
   * Can a player standing at {@code (fx, fy)} interact with fully-blocked scenery occupying
   * {@code (tx, ty)} — a furnace, range, ladder, tree, fishing spot? True iff the tiles are
   * <em>cardinally</em> adjacent and the shared edge carries no wall in either tile's flag
   * representation ({@link #canEngage} both directions: the from-side arm is the server's
   * {@code PathValidation.checkAdjacentDistance}, the object-side arm is the wall mask
   * {@code Mob.canReach} consults).
   *
   * <p>This is the goal predicate for pathing to blocked scenery — the
   * planner substitutes the original tile with interactable neighbours, and the walker's arrival
   * check accepts exactly the same tiles. A tile that is merely Chebyshev-1 away does NOT qualify:
   * a diagonal, or a cardinal on the far side of a building wall, cannot fire the action (the
   * classic failure: scenery in a room corner, and a radius-style arrival check "arriving" outside
   * the building).
   *
   * <p>The N-of-object cardinal (smaller y) IS accepted even though the
   * server's {@code Mob.canReach} lacks that case — {@code BotImpl}'s engagement walk-pair covers
   * it at the cost of one extra tick (see openrsc_canreach_asymmetry); callers rank it below
   * W/E/S.
   */
  public boolean canInteract(int fx, int fy, int tx, int ty, WallOverlay overlay) {
    int dx = tx - fx, dy = ty - fy;
    if (dx * dx + dy * dy != 1) {
      return false; // cardinal adjacency only
    }
    return canEngage(fx, fy, tx, ty, overlay)
           && canEngage(tx, ty, fx, fy, overlay);
  }

  // ===================================================================
  // Point / Area overloads — the geometry-typed face of the wall-aware
  // primitives above. Thin delegations so scripts and the pathfinder can
  // speak openrsc.bot.api.geometry types instead of loose int pairs.
  //
  // Every wall-consulting method takes an EXPLICIT WallOverlay — there is
  // deliberately no overlay-defaulting convenience overload. A hidden
  // null overlay silently drops the dynamic layer (closed doors), giving
  // static-only answers where the caller expected "right now"; that trap
  // is worth a few extra characters at every call site. Pass the bot's
  // live overlay for current topology (static + dynamic), or null to
  // deliberately opt into static-only (e.g. the pathfinder base graph).
  // Only inBounds — pure geometry, no walls — has no overlay parameter.
  // ===================================================================

  /**
   * {@link #inBounds(int, int)} for a {@link Point}. Pure geometry — no wall topology, no overlay.
   */
  public boolean inBounds(Point p) {
    return inBounds(p.x(), p.y());
  }

  /**
   * {@link #fullyBlocked(int, int, WallOverlay)} for a {@link Point}. Pass the live overlay for
   * current blockage (static + dynamic), or {@code null} for static-only.
   */
  public boolean fullyBlocked(Point p, WallOverlay overlay) {
    return fullyBlocked(p.x(), p.y(), overlay);
  }

  /**
   * True if {@code p} is in bounds and not {@link #fullyBlocked(Point, WallOverlay) fully blocked} —
   * a tile the bot could stand on (walls gate movement <em>between</em> tiles, not the tile itself).
   * Pass the live overlay for current state, or {@code null} for static-only.
   */
  public boolean walkable(Point p, WallOverlay overlay) {
    return inBounds(p) && !fullyBlocked(p, overlay);
  }

  /**
   * {@link #canStep(int, int, int, int, WallOverlay)} ({@code from} → {@code to}).
   */
  public boolean canStep(Point from, Point to, WallOverlay overlay) {
    return canStep(from.x(), from.y(), to.x(), to.y(), overlay);
  }

  /**
   * {@link #canEngage(int, int, int, int, WallOverlay)} ({@code from} → {@code to}).
   */
  public boolean canEngage(Point from, Point to, WallOverlay overlay) {
    return canEngage(from.x(), from.y(), to.x(), to.y(), overlay);
  }

  /**
   * {@link #canInteract(int, int, int, int, WallOverlay)} ({@code from} → {@code to}).
   */
  public boolean canInteract(Point from, Point to, WallOverlay overlay) {
    return canInteract(from.x(), from.y(), to.x(), to.y(), overlay);
  }

  /**
   * The tiles reachable in one step from {@code from} — its walkable 8-neighbours that
   * {@link #canStep} accepts (wall- and corner-clip-aware). The "where can I step from here"
   * primitive. Order is cardinals (N, E, S, W) then diagonals. Pass the bot's live overlay for
   * current topology (static + dynamic), or {@code null} for static-only.
   */
  public List<Point> steppableNeighbours(Point from, WallOverlay overlay) {
    List<Point> out = new ArrayList<>(8);
    for (Point n : from.neighbours()) {
      if (canStep(from.x(), from.y(), n.x(), n.y(), overlay)) {
        out.add(n);
      }
    }
    return out;
  }

  /**
   * The tiles a player can interact with an object from, given the tile region the object occupies —
   * the wall-aware "click/use/talk works from here" set the server's {@code Mob.atObject}
   * rect-membership {@code canReach} accepts. <b>Works for an object of any size:</b> pass a
   * {@link Point} for a 1×1 ladder/furnace or a {@link Rect} ({@code bot.footprintOf(obj)} /
   * {@code obj.footprint(defs)}) for a 2×2 staircase — the {@link Area} parameter takes either, so a
   * caller need not special-case size.
   *
   * <p>This is the wall-aware filter of {@link Area#surroundingTiles()} (plus, for wall sceneries,
   * the walkable interior). It mirrors {@code LadderTransport.enumerateEntriesRect}: every walkable
   * tile inside the rect (wall sceneries you stand on top of), plus every walkable ring tile that
   * can {@link #canEngage} its clamped-nearest rect tile (blocked sceneries you stand beside). For a
   * fully-blocked footprint the inside tiles drop out and only the engageable ring survives, so a
   * furnace/ladder/staircase yields exactly the tiles a click fires from; for a 1×1 object this
   * reduces to the proven single-tile {@code enumerateEntries} (anchor-if-walkable + the 8 neighbours
   * that {@code canEngage} it). Returns the tiles in row-major ring scan order; rank them with
   * {@code Bot.travelCost} to pick where to stand.
   *
   * <p>Pass the bot's live overlay for current topology (static + dynamic) — a closed door on the
   * footprint edge then drops the tile behind it; pass {@code null} to deliberately enumerate against
   * static topology only (the pathfinder's entry enumeration does this, since dynamic walls only add
   * blockage and a static-clear side is reachable once the walker opens the door en route).
   */
  public List<Point> interactableTiles(Area area, WallOverlay overlay) {
    int minX = area.minX(), minY = area.minY(), maxX = area.maxX(), maxY = area.maxY();
    List<Point> out = new ArrayList<>(2 * (area.width() + area.height()) + area.tileCount());
    for (int ny = minY - 1; ny <= maxY + 1; ny++) {
      for (int nx = minX - 1; nx <= maxX + 1; nx++) {
        if (!inBounds(nx, ny) || fullyBlocked(nx, ny, overlay)) {
          continue;
        }
        boolean inside = nx >= minX && nx <= maxX && ny >= minY && ny <= maxY;
        if (inside) {
          out.add(new Point(nx, ny));
          continue;
        }
        int cx = Math.max(minX, Math.min(maxX, nx));
        int cy = Math.max(minY, Math.min(maxY, ny));
        if (canEngage(nx, ny, cx, cy, overlay)) {
          out.add(new Point(nx, ny));
        }
      }
    }
    return out;
  }

  public int width() {
    return WIDTH;
  }

  public int height() {
    return HEIGHT;
  }

  /**
   * Raw {@code groundTexture} byte; index into the 256-entry biome palette.
   */
  public int groundTexture(int x, int absY) {
    if (!inBounds(x, absY)) {
      return 0;
    }
    return groundTexture[absY * WIDTH + x] & 0xFF;
  }

  /**
   * Raw {@code groundOverlay} byte (1-based TileDefs id; 0 = none).
   */
  public int groundOverlay(int x, int absY) {
    if (!inBounds(x, absY)) {
      return 0;
    }
    return groundOverlay[absY * WIDTH + x] & 0xFF;
  }

  /**
   * Raw {@code roofTexture} byte from the landscape archive. Used by the web UI's renderer only —
   * pathfinding deliberately does NOT gate F1+ walkability on it: the server permits walking
   * upper-floor void (proven by {@code ServerAcceptsVoidWalkIT}), so a roof gate would be more
   * restrictive than the server.
   */
  public int roofTexture(int x, int absY) {
    if (!inBounds(x, absY)) {
      return 0;
    }
    return roofTexture[absY * WIDTH + x] & 0xFF;
  }
}
