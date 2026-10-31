package openrsc.gamedata.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.defs.TileDefs;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.landscape.RawSector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The collision of the whole world, built from a landscape and the static locs: each tile's flag
 * byte as the server's {@code TileValue.traversalMask} holds it, and its projectile clip. The walk,
 * reach and line-of-sight tests below are the server's own rules over those flags; each names the
 * server method it mirrors.
 *
 * <p>Tiles are absolute. The four floors share one plane: floor {@code n} is
 * {@code y = n * FLOOR_HEIGHT + floorLocalY}, and a wall along each seam keeps a walk on its floor.
 * In RSC east is toward lower x and north toward lower y. {@link #WALL_NORTH} on a tile walls its
 * north edge, {@link #WALL_EAST} its east edge and so on; every edge is stamped on both tiles that
 * share it.
 *
 * <p>Generic free doors ({@link DoorDefs#isGenericFreeDoor}, {@link ObjectDefs#isGenericFreeDoor})
 * are never stamped, so the map reads them open.
 *
 * <p>Methods taking a {@link LocalOverlay} read the map through it ({@link #flags(int, int,
 * LocalOverlay)}); {@code null} reads the map as built. Methods taking {@link Overlays} read its
 * walk layer to walk or stand and its line-of-sight layer for reach and line of sight. Immutable
 * once built, so safe to share across threads.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L337">WorldLoader.loadSection</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L489">World.registerGameObject</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L523">PathValidation.checkAdjacent</a>
 */
public final class CollisionMap {

    /**
     * How far a trade request reaches, in tiles of truncated pythagoras.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L98">PlayerTradeHandler.process</a>
     */
    public static final int TRADE_RANGE = 4;

    /**
     * Changes whenever a change to the step rules can change which steps are legal, so step data
     * kept outside the process can be recognised as stale.
     */
    public static final int STEP_RULES_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(CollisionMap.class);

    /**
     * Tiles a mob blocks this tick. The step tests consult it where the server's step test consults
     * mob blocking: at the destination and, for a diagonal, at both flanks, never at the mover's own
     * tile.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L625">PathValidation.checkBlocking</a>
     */
    @FunctionalInterface
    public interface TileOccupancy {

        boolean blockedAt(int x, int y);
    }

    /**
     * A wall on the tile's north edge. The flag bits are the server's {@code CollisionFlag} bits.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/CollisionFlag.java#L4">CollisionFlag.WALL_NORTH</a>
     */
    public static final int WALL_NORTH = 1 << 0;

    public static final int WALL_EAST = 1 << 1;
    public static final int WALL_SOUTH = 1 << 2;
    public static final int WALL_WEST = 1 << 3;
    /** A diagonal wall: a dir-2 boundary, or a landscape diagonal of 12001..23999. */
    public static final int FULL_BLOCK_A = 1 << 4;
    /** The other diagonal wall: a dir-3 boundary, or a landscape diagonal of 1..11999. */
    public static final int FULL_BLOCK_B = 1 << 5;
    /** A solid block: def-type 1 scenery, a blocking ground overlay, or no landscape at all. */
    public static final int FULL_BLOCK_C = 1 << 6;

    public static final int FULL_BLOCK = FULL_BLOCK_A | FULL_BLOCK_B | FULL_BLOCK_C;
    /**
     * Set only by an overlay: a projectile passes over the tile, as over a {@link #projectileAllowed}
     * one, because of a projectile-clip scenery the server spawned there.
     */
    public static final int PROJECTILE_PASS = 1 << 7;

    // The server's Constants.objectsProjectileClipAllowed, compared ignoring case.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Constants.java#L93
    private static final Set<String> PROJECTILE_CLIP_ALLOWED_NAMES = Set.of(
            "gravestone",
            "sign",
            "broken pillar",
            "bone",
            "animalskull",
            "skull",
            "egg",
            "eggs",
            "ladder",
            "torch",
            "rock",
            "treestump",
            "railing",
            "railings",
            "gate",
            "fence",
            "table",
            "smashed chair",
            "smashed table",
            "longtable",
            "wooden gate",
            "metal gate",
            "chair");

    // Landscape wall values (def id + 1) a projectile passes over.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L26
    private static final Set<Integer> PROJECTILE_CLIP_ALLOWED_WALL_IDS = Set.of(5, 6, 14, 42, 63, 128, 229, 230);

    /**
     * The server's region and landscape sector edge, in tiles.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Constants.java#L77">Constants.REGION_SIZE</a>
     */
    public static final int REGION_SIZE = 48;

    /**
     * The landscape sections the server loads on each floor: x 48..68, y 37..56, inclusive.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L527">WorldLoader.loadWorld</a>
     */
    public static final int LOAD_REGION_X_FROM = 48;

    public static final int LOAD_REGION_X_TO = 68;
    public static final int LOAD_REGION_Y_FROM = 37;
    public static final int LOAD_REGION_Y_TO = 56;
    static final int REGIONS_X = LOAD_REGION_X_TO - LOAD_REGION_X_FROM + 1;

    public static final int FLOOR_COUNT = 4;
    /**
     * The y distance between floors. The last section row of a floor runs 16 tiles past it, into the
     * next floor's rows, on the server too.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L528">WorldLoader.loadWorld</a>
     */
    public static final int FLOOR_HEIGHT = 944;

    public static final int WIDTH = REGIONS_X * REGION_SIZE;
    public static final int HEIGHT = FLOOR_COUNT * FLOOR_HEIGHT;

    private final byte[] flags;

    // The part of flags the static locs contributed: what a live view replaces. Terrain, landscape
    // walls and seams are not in it; the server never streams them.
    private final byte[] objectFlags;

    private final boolean[] projectileAllowed;

    private final short[] components;

    // Per tile, the canStep answer for each of the 8 neighbours with no overlay and no mob, bit
    // stepBit(dx, dy).
    private final byte[] staticSteps;

    private int componentCount;

    private final StaticLocIndex staticLocIndex;

    private final ObjectDefs objectDefs;

    private final byte[] groundTexture;

    private final byte[] groundOverlay;

    private final byte[] roofTexture;

    /**
     * Builds the map: the landscape's sectors, then the static locs' collision and projectile clip.
     * A tile with no sector stays fully blocked, as on the server. {@code scenery} and
     * {@code boundaries} may be {@code null} to stamp none. {@code landscape} is read, not closed.
     *
     * @throws NullPointerException when {@code objectDefs} is {@code null}
     */
    public CollisionMap(
            LandscapeSource landscape,
            DoorDefs doorDefs,
            TileDefs tileDefs,
            List<SceneryLocs.Loc> scenery,
            ObjectDefs objectDefs,
            List<BoundaryLocs.Loc> boundaries) {
        this.objectDefs = Objects.requireNonNull(
                objectDefs, "objectDefs — a CollisionMap must carry the scenery defs for engagement geometry");
        int tiles = WIDTH * HEIGHT;
        this.flags = new byte[tiles];
        this.objectFlags = new byte[tiles];
        this.projectileAllowed = new boolean[tiles];
        this.components = new short[tiles];
        this.staticSteps = new byte[tiles];
        this.groundTexture = new byte[tiles];
        this.groundOverlay = new byte[tiles];
        this.roofTexture = new byte[tiles];
        Arrays.fill(flags, (byte) FULL_BLOCK_C);
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
                            int idx = x * REGION_SIZE + y;
                            stampTile(
                                    bx,
                                    by,
                                    s.groundTexture[idx] & 0xFF,
                                    s.groundOverlay[idx] & 0xFF,
                                    s.roofTexture[idx] & 0xFF,
                                    s.horizontalWall[idx] & 0xFF,
                                    s.verticalWall[idx] & 0xFF,
                                    s.diagonalWalls[idx],
                                    doorDefs,
                                    tileDefs);
                        }
                    }
                }
            }
            if (missing > 0) {
                LOG.debug("landscape floor {}: {} missing sections (expected on sparse upper floors)", floor, missing);
            }
        }
        this.staticLocIndex = StaticLocIndex.build(scenery, objectDefs, boundaries, doorDefs);
        staticLocIndex.forEachContribution(this::orObject);
        LOG.info(
                "stamped {}/{} scenery and {}/{} boundary locs into collision map",
                staticLocIndex.stampedSceneryCount(),
                staticLocIndex.sceneryCount(),
                staticLocIndex.stampedBoundaryCount(),
                staticLocIndex.boundaryCount());
        if (scenery != null && objectDefs != null) {
            int clip = applySceneryProjectileClip(scenery, objectDefs);
            LOG.info("stamped {} scenery projectile-clip tiles into collision map", clip);
        }
        if (boundaries != null && doorDefs != null) {
            int clip = applyBoundaryProjectileClip(boundaries, doorDefs);
            LOG.info("stamped {} boundary projectile-clip tiles into collision map", clip);
        }
        sealFloorSeams();
        long stepsStart = System.nanoTime();
        buildStaticSteps();
        LOG.info("baked static steps in {}ms", (System.nanoTime() - stepsStart) / 1_000_000);
        computeComponents();
    }

    /** Every per-tile plane of a map, each {@code WIDTH * HEIGHT} long and indexed {@code y * WIDTH + x}. */
    public record Planes(
            byte[] flags,
            byte[] objectFlags,
            boolean[] projectileAllowed,
            short[] components,
            int componentCount,
            byte[] staticSteps,
            byte[] groundTexture,
            byte[] groundOverlay,
            byte[] roofTexture) {}

    /** This map's planes: the live arrays, not copies, so a caller must not write them. */
    public Planes planes() {
        return new Planes(
                flags,
                objectFlags,
                projectileAllowed,
                components,
                componentCount,
                staticSteps,
                groundTexture,
                groundOverlay,
                roofTexture);
    }

    /**
     * A map over {@code planes} taken from a built one, adopting the arrays, with the loc index
     * rebuilt from the locs the planes were stamped from.
     *
     * @throws IllegalArgumentException when a plane is not {@code WIDTH * HEIGHT} long
     */
    public static CollisionMap restore(
            Planes planes,
            List<SceneryLocs.Loc> scenery,
            ObjectDefs objectDefs,
            List<BoundaryLocs.Loc> boundaries,
            DoorDefs doorDefs) {
        return new CollisionMap(planes, StaticLocIndex.build(scenery, objectDefs, boundaries, doorDefs), objectDefs);
    }

    private CollisionMap(Planes p, StaticLocIndex index, ObjectDefs objectDefs) {
        int tiles = WIDTH * HEIGHT;
        if (p.flags().length != tiles
                || p.objectFlags().length != tiles
                || p.projectileAllowed().length != tiles
                || p.components().length != tiles
                || p.staticSteps().length != tiles
                || p.groundTexture().length != tiles
                || p.groundOverlay().length != tiles
                || p.roofTexture().length != tiles) {
            throw new IllegalArgumentException("planes are not " + WIDTH + "x" + HEIGHT);
        }
        this.objectDefs = Objects.requireNonNull(objectDefs, "objectDefs");
        this.flags = p.flags();
        this.objectFlags = p.objectFlags();
        this.projectileAllowed = p.projectileAllowed();
        this.components = p.components();
        this.componentCount = p.componentCount();
        this.staticSteps = p.staticSteps();
        this.groundTexture = p.groundTexture();
        this.groundOverlay = p.groundOverlay();
        this.roofTexture = p.roofTexture();
        this.staticLocIndex = index;
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT;
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    /** The tile's flag byte as built; {@link #FULL_BLOCK} off the map. */
    public int flags(int x, int y) {
        if (!inBounds(x, y)) {
            return FULL_BLOCK;
        }
        return flags[y * WIDTH + x] & 0xFF;
    }

    /**
     * The tile's flag byte read through {@code overlay}. In a chunk the overlay {@link
     * LocalOverlay#isLive is live} for, the stream replaces the static locs' collision, so a loc the
     * server removed reads gone; elsewhere the overlay only adds. Terrain, landscape walls and floor
     * seams stand either way. A {@code null} overlay reads the map as built.
     */
    public int flags(int x, int y, LocalOverlay overlay) {
        int f = flags(x, y);
        if (overlay == null) {
            return f;
        }
        if (overlay.isLive(x, y)) {
            return (f & ~objectFlags(x, y)) | overlay.flags(x, y);
        }
        return f | overlay.flags(x, y);
    }

    /** The part of {@link #flags(int, int)} the static locs contributed; 0 off the map. */
    public int objectFlags(int x, int y) {
        if (!inBounds(x, y)) {
            return 0;
        }
        return objectFlags[y * WIDTH + x] & 0xFF;
    }

    public boolean fullyBlocked(int x, int y) {
        return (flags(x, y) & FULL_BLOCK) != 0;
    }

    public boolean fullyBlocked(int x, int y, LocalOverlay overlay) {
        return (flags(x, y, overlay) & FULL_BLOCK) != 0;
    }

    /**
     * Whether a projectile passes over the tile, though it may block walking: the server's
     * {@code TileValue.projectileAllowed} as the world loads. {@code false} off the map.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/region/TileValue.java#L12">TileValue.projectileAllowed</a>
     */
    public boolean projectileAllowed(int x, int y) {
        return inBounds(x, y) && projectileAllowed[y * WIDTH + x];
    }

    public boolean checkAdjacent(int fx, int fy, int tx, int ty) {
        return checkAdjacent(fx, fy, tx, ty, (LocalOverlay) null, null);
    }

    public boolean checkAdjacent(int fx, int fy, int tx, int ty, LocalOverlay overlay) {
        return checkAdjacent(fx, fy, tx, ty, overlay, null);
    }

    /**
     * Whether a mob may walk from {@code (fx, fy)} to the neighbouring {@code (tx, ty)}: the
     * server's per-step walk test. {@code false} for a tile that is not a neighbour, or off the map.
     * {@code mob} may be {@code null} for no mob blocking.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L523">PathValidation.checkAdjacent</a>
     */
    public boolean checkAdjacent(int fx, int fy, int tx, int ty, LocalOverlay overlay, TileOccupancy mob) {
        if (!inBounds(tx, ty) || fullyBlocked(tx, ty, overlay)) {
            return false;
        }
        int dx = tx - fx, dy = ty - fy;
        int from = flags(fx, fy, overlay);
        if (dy == 0 || dx == 0) {
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
     * Whether {@code (fx, fy)} to {@code (tx, ty)} is one step, taken in one tick: {@link
     * #checkAdjacent} allows it and, for a diagonal, both flanks are open or both shut. With one flank
     * open the server's path builder walks that flank first, so the diagonal takes two steps.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Path.java#L102">Path.addStep</a>
     */
    public boolean canStep(int fx, int fy, int tx, int ty, LocalOverlay overlay, TileOccupancy mob) {
        int dx = tx - fx;
        int dy = ty - fy;
        if (mob == null
                && inBounds(fx, fy)
                && (dx | dy) != 0
                && dx >= -1
                && dx <= 1
                && dy >= -1
                && dy <= 1
                && (overlay == null || (overlay instanceof ChunkBoxOverlay box && box.agreesAround(this, fx, fy)))) {
            // Nothing here differs from the map as built, so the precomputed answer holds.
            return (staticSteps[fy * WIDTH + fx] & 1 << stepBit(dx, dy)) != 0;
        }
        return canStepByRule(fx, fy, tx, ty, overlay, mob);
    }

    private boolean canStepByRule(int fx, int fy, int tx, int ty, LocalOverlay overlay, TileOccupancy mob) {
        if (!checkAdjacent(fx, fy, tx, ty, overlay, mob)) {
            return false;
        }
        if (fx == tx || fy == ty) {
            return true;
        }
        boolean flankX = checkAdjacent(fx, fy, tx, fy, overlay, mob);
        boolean flankY = checkAdjacent(fx, fy, fx, ty, overlay, mob);
        return flankX == flankY;
    }

    /**
     * A copy of the {@link #canStep} answers with no overlay and no mob: one byte per tile, indexed
     * {@code y * WIDTH + x}, with bit {@code (dy + 1) * 3 + dx + 1} set, less one past the centre,
     * when the step {@code (dx, dy)} is legal.
     */
    public byte[] staticSteps() {
        return staticSteps.clone();
    }

    private static int stepBit(int dx, int dy) {
        int d = (dy + 1) * 3 + dx + 1;
        return d < 4 ? d : d - 1;
    }

    private void buildStaticSteps() {
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int bits = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx | dy) != 0 && canStepByRule(x, y, x + dx, y + dy, null, null)) {
                            bits |= 1 << stepBit(dx, dy);
                        }
                    }
                }
                staticSteps[y * WIDTH + x] = (byte) bits;
            }
        }
    }

    public boolean canStep(int fx, int fy, int tx, int ty) {
        return canStep(fx, fy, tx, ty, (LocalOverlay) null, null);
    }

    public boolean canStep(int fx, int fy, int tx, int ty, LocalOverlay overlay) {
        return canStep(fx, fy, tx, ty, overlay, null);
    }

    /**
     * The radius a melee-shaped mob action reaches: talk, command, use on an NPC, and an attack at
     * the {@code pvm_catching_distance} and {@code pvp_catching_distance} uranium.conf sets.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/NpcTalkTo.java#L41">NpcTalkTo.process</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/uranium.conf#L88">pvm_catching_distance</a>
     */
    public static final int MOB_REACH_RADIUS = 1;

    /**
     * Whether a melee-shaped action from {@code (fx, fy)} lands on a mob at {@code (tx, ty)}: within
     * {@link #MOB_REACH_RADIUS} and a clear adjacent line. A diagonal passes one flank blocked by solid
     * scenery, but not two. {@code (fx, fy)} is the tile the server tests: the player's next tile while
     * walking.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/action/WalkToMobAction.java#L36">WalkToMobAction.shouldExecuteInternal</a>
     */
    public boolean canReachMob(int fx, int fy, int tx, int ty, LocalOverlay overlay) {
        return Point.pythagoras(fx, fy, tx, ty) <= MOB_REACH_RADIUS
                && checkAdjacentDistance(fx, fy, tx, ty, true, false, overlay);
    }

    /**
     * Whether a stopped player on {@code (px, py)} reaches block scenery covering the inclusive rect:
     * standing in it, or on a neighbour whose walls toward it are clear. Walls only, no full blocks.
     * An empty rect ({@code minX > maxX}) is reached from nowhere.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L238">Mob.canReach</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L257">Mob.canReachDiagonal</a>
     */
    public boolean canReachScenery(int px, int py, int minX, int maxX, int minY, int maxY, LocalOverlay overlay) {
        if (px >= minX && px <= maxX && py >= minY && py <= maxY) {
            return true;
        }
        if (minX <= px - 1
                && maxX >= px - 1
                && minY <= py
                && maxY >= py
                && (flags(px - 1, py, overlay) & WALL_WEST) == 0) {
            return true;
        }
        if (px + 1 >= minX
                && px + 1 <= maxX
                && minY <= py
                && maxY >= py
                && (flags(px + 1, py, overlay) & WALL_EAST) == 0) {
            return true;
        }
        if (minX <= px
                && maxX >= px
                && py - 1 >= minY
                && maxY >= py - 1
                && (flags(px, py - 1, overlay) & WALL_SOUTH) == 0) {
            return true;
        }
        if (minX <= px
                && px <= maxX
                && minY <= py + 1
                && maxY >= py + 1
                && (flags(px, py + 1, overlay) & WALL_NORTH) == 0) {
            return true;
        }
        if (minX <= px - 1
                && maxX >= px - 1
                && minY <= py - 1
                && maxY >= py - 1
                && (flags(px - 1, py - 1, overlay) & (WALL_SOUTH | WALL_WEST)) == 0) {
            return true;
        }
        if (px + 1 >= minX
                && px + 1 <= maxX
                && py - 1 >= minY
                && maxY >= py - 1
                && (flags(px + 1, py - 1, overlay) & (WALL_SOUTH | WALL_EAST)) == 0) {
            return true;
        }
        if (minX <= px - 1
                && maxX >= px - 1
                && minY <= py + 1
                && maxY >= py + 1
                && (flags(px - 1, py + 1, overlay) & (WALL_NORTH | WALL_WEST)) == 0) {
            return true;
        }
        if (px + 1 >= minX
                && px + 1 <= maxX
                && py + 1 >= minY
                && maxY >= py + 1
                && (flags(px + 1, py + 1, overlay) & (WALL_NORTH | WALL_EAST)) == 0) {
            return true;
        }
        return false;
    }

    /**
     * Whether a projectile's line from one tile to another is clear, stepping toward the target one
     * tile at a time. Unlike walking, a {@link #projectileAllowed} or {@link #PROJECTILE_PASS} tile
     * never blocks. A closed door does, so pass the door-honest layer. {@code true} for one tile.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L35">PathValidation.checkPath</a>
     */
    public boolean checkPath(int fromX, int fromY, int toX, int toY, LocalOverlay overlay) {
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
            if (!checkAdjacentDistance(curX, curY, nextX, nextY, false, true, overlay)) {
                return false;
            }
            curX = nextX;
            curY = nextY;
        }
        return true;
    }

    /**
     * The tile's walking component as built: tiles sharing an id connect by walking alone. Ids run
     * from 1; 0 is a fully blocked tile or off the map.
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

    public StaticLocIndex staticLocIndex() {
        return staticLocIndex;
    }

    /** The scenery def for {@code id}, or {@code null} when the id is unknown. */
    public ObjectDef objectDef(int id) {
        return objectDefs.get(id);
    }

    /**
     * Whether the server accepts an action on {@code t} from {@code (fx, fy)}. The server has no one
     * reach: each target kind has its own test, and this dispatches to the one {@code t}'s kind uses.
     */
    public boolean canEngage(int fx, int fy, Targetable t, LocalOverlay overlay) {
        return switch (t.kind()) {
            case NPC, PLAYER -> canReachMob(fx, fy, t.x(), t.z(), overlay);
            case PLAYER_TRADE -> withinTradeRange(fx, fy, t.x(), t.z(), overlay);
            case PLAYER_USE ->
                canReachMob(fx, fy, t.x(), t.z(), overlay) && canReachPlayer(fx, fy, t.x(), t.z(), overlay);
            case NPC_SPAWN -> reachesMobInPlace(fx, fy, t.x(), t.z(), overlay);
            case SCENERY -> atScenery(fx, fy, t.id(), t.x(), t.z(), t.dir(), overlay);
            case BOUNDARY -> atBoundary(fx, fy, t.x(), t.z(), t.dir());
            case GROUND -> canTakeGroundItem(fx, fy, t.x(), t.z(), overlay);
        };
    }

    /**
     * Whether a trade request between the two tiles carries: within {@link #TRADE_RANGE} and a clear
     * {@link #checkPath} line both ways, since each player's side tests from its own tile.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L104">PlayerTradeHandler.process</a>
     */
    public boolean withinTradeRange(int fx, int fy, int tx, int ty, LocalOverlay overlay) {
        return Point.pythagoras(fx, fy, tx, ty) <= TRADE_RANGE
                && checkPath(fx, fy, tx, ty, overlay)
                && checkPath(tx, ty, fx, fy, overlay);
    }

    /**
     * {@link #canReachMob}, less the diagonals with exactly one open flank: from one of those the
     * server's path builder steps onto the open flank first, so the action lands from there.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Path.java#L127">Path.addStep</a>
     */
    public boolean reachesMobInPlace(int fx, int fy, int tx, int ty, LocalOverlay overlay) {
        if (!canReachMob(fx, fy, tx, ty, overlay)) {
            return false;
        }
        int dx = tx - fx;
        int dy = ty - fy;
        if (dx == 0 || dy == 0) {
            return true;
        }
        return checkAdjacent(fx, fy, tx, fy, overlay) == checkAdjacent(fx, fy, fx, ty, overlay);
    }

    /**
     * Whether a stopped player on {@code (px, py)} reaches scenery {@code id} placed at {@code (x, z)}
     * facing {@code dir}. Wall-type scenery (def type 2 or 3) is reached from any tile of its
     * boundary, with no collision read; other scenery by {@link #canReachScenery}, and runecraft
     * altars from further out. An unknown id is a 1x1 block.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L197">Mob.atObject</a>
     */
    public boolean atScenery(int px, int py, int id, int x, int z, int dir, LocalOverlay overlay) {
        ObjectDef def = objectDef(id);
        Bounds b = sceneryBounds(def, x, z, dir);
        if (isWallScenery(def)) {
            return b.contains(px, py);
        }
        return canReachScenery(px, py, b.minX(), b.maxX(), b.minY(), b.maxY(), overlay)
                || closeSpecObject(px, py, id, b);
    }

    /**
     * Whether a player on {@code (px, py)} reaches a boundary at {@code (x, z)}: from either tile of
     * its edge, or the 3x3 around it for a diagonal, with no collision read.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/GameObject.java#L86">GameObject.getObjectBoundary</a>
     */
    public static boolean atBoundary(int px, int py, int x, int z, int dir) {
        return boundaryBounds(x, z, dir).contains(px, py);
    }

    /**
     * Whether a take from {@code (px, py)} picks up an item on {@code (tx, ty)}: from the item's tile,
     * or a neighbour when static scenery has its origin there; from the same server region; and with
     * the item reachable. A player in combat or a blocking NPC on the item's tile also widens the
     * server's reach; neither is modelled.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GroundItemTake.java#L51">GroundItemTake.process</a>
     */
    public boolean canTakeGroundItem(int px, int py, int tx, int ty, LocalOverlay overlay) {
        int radius = staticLocIndex.hasSceneryAt(tx, ty) ? 1 : 0;
        return Point.pythagoras(px, py, tx, ty) <= radius
                && sameServerRegion(px, py, tx, ty)
                && canReachGroundItem(px, py, tx, ty, overlay);
    }

    /**
     * Whether two tiles lie in one of the server's 48x48 regions, as a ground-item action looks the
     * item up in the player's own region.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/region/RegionManager.java#L198">RegionManager.getRegion</a>
     */
    public static boolean sameServerRegion(int ax, int ay, int bx, int by) {
        return ax / REGION_SIZE == bx / REGION_SIZE && ay / REGION_SIZE == by / REGION_SIZE;
    }

    /**
     * Every tile a player can stand on from which {@link #canEngage} accepts {@code t}, except the
     * tile an NPC or player target stands on.
     */
    public List<Point> engageTiles(Targetable t, LocalOverlay overlay) {
        Bounds window =
                switch (t.kind()) {
                    case SCENERY -> {
                        Bounds b = sceneryBounds(objectDef(t.id()), t.x(), t.z(), t.dir());
                        int m = closeSpecReach(t.id());
                        yield new Bounds(b.minX() - m, b.minY() - m, b.maxX() + m, b.maxY() + m);
                    }
                    case BOUNDARY -> boundaryBounds(t.x(), t.z(), t.dir());
                    case NPC, PLAYER, PLAYER_USE, NPC_SPAWN, GROUND ->
                        new Bounds(t.x() - 1, t.z() - 1, t.x() + 1, t.z() + 1);
                    case PLAYER_TRADE ->
                        new Bounds(t.x() - TRADE_RANGE, t.z() - TRADE_RANGE, t.x() + TRADE_RANGE, t.z() + TRADE_RANGE);
                };
        boolean mob = t.kind() == Targetable.Kind.NPC
                || t.kind() == Targetable.Kind.PLAYER
                || t.kind() == Targetable.Kind.PLAYER_USE;
        List<Point> out = new ArrayList<>();
        for (int y = window.minY(); y <= window.maxY(); y++) {
            for (int x = window.minX(); x <= window.maxX(); x++) {
                if (mob && x == t.x() && y == t.z()) {
                    continue;
                }
                if (inBounds(x, y) && !fullyBlocked(x, y, overlay) && canEngage(x, y, t, overlay)) {
                    out.add(new Point(x, y));
                }
            }
        }
        return out;
    }

    /** Every tile a player can stand on from which {@link #canReachScenery} reaches {@code footprint}. */
    public List<Point> engageTilesAround(Rect footprint, LocalOverlay overlay) {
        List<Point> out = new ArrayList<>();
        for (int ny = footprint.minY() - 1; ny <= footprint.maxY() + 1; ny++) {
            for (int nx = footprint.minX() - 1; nx <= footprint.maxX() + 1; nx++) {
                if (inBounds(nx, ny)
                        && !fullyBlocked(nx, ny, overlay)
                        && canReachScenery(
                                nx,
                                ny,
                                footprint.minX(),
                                footprint.maxX(),
                                footprint.minY(),
                                footprint.maxY(),
                                overlay)) {
                    out.add(new Point(nx, ny));
                }
            }
        }
        return out;
    }

    /**
     * How far a spell reaches a mob, in tiles of truncated pythagoras.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1378">SpellHandler.handleMobCast</a>
     */
    public static final int SPELL_RANGE = 4;

    /**
     * Whether a ranged attack or spell at {@code (tx, ty)} is taken from {@code (fx, fy)}: within
     * {@code range} tiles of truncated pythagoras, and a clear {@link #checkPath} line.
     */
    public boolean resolvesFrom(int fx, int fy, int tx, int ty, int range, LocalOverlay overlay) {
        return Point.pythagoras(fx, fy, tx, ty) <= range && checkPath(fx, fy, tx, ty, overlay);
    }

    /** Every tile a player can stand on that {@link #resolvesFrom} accepts for {@code (tx, ty)}. */
    public List<Point> rangeTiles(int tx, int ty, int range, LocalOverlay overlay) {
        List<Point> out = new ArrayList<>();
        for (int y = ty - range; y <= ty + range; y++) {
            for (int x = tx - range; x <= tx + range; x++) {
                if (inBounds(x, y) && !fullyBlocked(x, y, overlay) && resolvesFrom(x, y, tx, ty, range, overlay)) {
                    out.add(new Point(x, y));
                }
            }
        }
        return out;
    }

    /**
     * The {@link #engageTiles} tile fewest steps from {@code (fx, fy)}, walking on the walk layer, or
     * {@code null} when none is reachable within 24 tiles of the start and those tiles.
     */
    public Point closestEngageTile(int fx, int fy, Targetable t, Overlays o) {
        List<Point> goals = engageTiles(t, o.losOverlay());
        LocalOverlay walk = o.walkOverlay();
        if (goals.isEmpty()) {
            return null;
        }
        int gMinX = fx;
        int gMaxX = fx;
        int gMinY = fy;
        int gMaxY = fy;
        for (Point g : goals) {
            gMinX = Math.min(gMinX, g.x());
            gMaxX = Math.max(gMaxX, g.x());
            gMinY = Math.min(gMinY, g.y());
            gMaxY = Math.max(gMaxY, g.y());
        }
        int xMin = gMinX - 24;
        int xMax = gMaxX + 24;
        int yMin = gMinY - 24;
        int yMax = gMaxY + 24;
        int width = xMax - xMin + 1;
        int height = yMax - yMin + 1;
        if ((long) width * height > 4 * 64 * 64) {
            return null;
        }
        boolean[] goal = new boolean[width * height];
        for (Point g : goals) {
            if (g.x() >= xMin && g.x() <= xMax && g.y() >= yMin && g.y() <= yMax) {
                goal[(g.y() - yMin) * width + (g.x() - xMin)] = true;
            }
        }
        int start = (fy - yMin) * width + (fx - xMin);
        if (goal[start]) {
            return new Point(fx, fy);
        }
        boolean[] seen = new boolean[width * height];
        int[] queue = new int[width * height];
        int qh = 0;
        int qt = 0;
        seen[start] = true;
        queue[qt++] = start;
        while (qh < qt) {
            int cur = queue[qh++];
            int cx = xMin + cur % width;
            int cy = yMin + cur / width;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx < xMin || ny < yMin || nx > xMax || ny > yMax) {
                        continue;
                    }
                    int nIdx = (ny - yMin) * width + (nx - xMin);
                    if (seen[nIdx] || !canStep(cx, cy, nx, ny, walk, null)) {
                        continue;
                    }
                    if (goal[nIdx]) {
                        return new Point(nx, ny);
                    }
                    seen[nIdx] = true;
                    queue[qt++] = nIdx;
                }
            }
        }
        return null;
    }

    private record Bounds(int minX, int minY, int maxX, int maxY) {
        boolean contains(int x, int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private static boolean isWallScenery(ObjectDef def) {
        return def != null && (def.typ() == 2 || def.typ() == 3);
    }

    // The server's boundary of a scenery placement, unclamped: a zero-size def gives an empty box.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/GameObject.java#L48
    private static Bounds sceneryBounds(ObjectDef def, int x, int z, int dir) {
        int w;
        int h;
        if (def == null) {
            w = 1;
            h = 1;
        } else if (dir != 0 && dir != 4) {
            w = def.height();
            h = def.width();
        } else {
            w = def.width();
            h = def.height();
        }
        int minX = x;
        int minY = z;
        if (isWallScenery(def)) {
            if (dir == 0) {
                w++;
                minX--;
            }
            if (dir == 2) {
                h++;
            }
            if (dir == 6) {
                minY--;
                h++;
            }
            if (dir == 4) {
                w++;
            }
        }
        return new Bounds(minX, minY, x + w - 1, z + h - 1);
    }

    private static Bounds boundaryBounds(int x, int z, int dir) {
        if (dir == 0) {
            return new Bounds(x, z - 1, x, z);
        }
        if (dir == 1) {
            return new Bounds(x - 1, z, x, z);
        }
        if (dir == 2 || dir == 3) {
            return new Bounds(x - 1, z - 1, x + 1, z + 1);
        }
        return new Bounds(x, z, x, z);
    }

    // The server's further reach for runecraft altars. Its 1228..1232 clause sits in the else of the
    // 1227 test and never matches, so it is left out.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L216
    private static boolean closeSpecObject(int px, int py, int id, Bounds b) {
        int lowXDiff = Math.abs(px - b.minX());
        int highXDiff = Math.abs(px - b.maxX());
        int lowYDiff = Math.abs(py - b.minY());
        int highYDiff = Math.abs(py - b.maxY());
        if (id >= 1190 && id <= 1225) {
            return (lowXDiff <= 2 || highXDiff <= 2) && (lowYDiff <= 2 || highYDiff <= 2);
        }
        if (id == 1227) {
            return (lowXDiff <= 2 || highXDiff <= 2) && highYDiff <= 3;
        }
        return false;
    }

    private static int closeSpecReach(int id) {
        return id >= 1190 && id <= 1232 ? 3 : 1;
    }

    private boolean canReachGroundItem(int px, int py, int ex, int ey, LocalOverlay overlay) {
        return canReachEntity(px, py, ex, ey, true, overlay);
    }

    public boolean canReachPlayer(int px, int py, int ex, int ey, LocalOverlay overlay) {
        return canReachEntity(px, py, ex, ey, false, overlay);
    }

    // Mob.canReach(Entity): walks Mob.nextStep greedily to the target, clause for clause, including
    // the second refusal's myYBlocked where newYBlocked would read more naturally.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L282
    private boolean canReachEntity(int px, int py, int ex, int ey, boolean groundItem, LocalOverlay overlay) {
        int x = px;
        int y = py;
        while (x != ex || y != ey) {
            int newX = x;
            int newY = y;
            boolean myXBlocked = false;
            boolean myYBlocked = false;
            boolean newXBlocked = false;
            boolean newYBlocked = false;
            if (x > ex) {
                myXBlocked = entityStepBlocked(x - 1, y, WALL_WEST, ex, ey, groundItem, overlay);
                newX = x - 1;
            } else if (x < ex) {
                myXBlocked = entityStepBlocked(x + 1, y, WALL_EAST, ex, ey, groundItem, overlay);
                newX = x + 1;
            }
            if (y > ey) {
                myYBlocked = entityStepBlocked(x, y - 1, WALL_SOUTH, ex, ey, groundItem, overlay);
                newY = y - 1;
            } else if (y < ey) {
                myYBlocked = entityStepBlocked(x, y + 1, WALL_NORTH, ex, ey, groundItem, overlay);
                newY = y + 1;
            }
            if ((myXBlocked && myYBlocked) || (myXBlocked && y == newY) || (myYBlocked && x == newX)) {
                return false;
            }
            if (newX > x) {
                newXBlocked = entityStepBlocked(newX, newY, WALL_EAST, ex, ey, groundItem, overlay);
            } else if (newX < x) {
                newXBlocked = entityStepBlocked(newX, newY, WALL_WEST, ex, ey, groundItem, overlay);
            }
            if (newY > y) {
                newYBlocked = entityStepBlocked(newX, newY, WALL_NORTH, ex, ey, groundItem, overlay);
            } else if (newY < y) {
                newYBlocked = entityStepBlocked(newX, newY, WALL_SOUTH, ex, ey, groundItem, overlay);
            }
            if ((newXBlocked && newYBlocked) || (newXBlocked && y == newY) || (myYBlocked && x == newX)) {
                return false;
            }
            if ((myXBlocked && newXBlocked) || (myYBlocked && newYBlocked)) {
                return false;
            }
            x = newX;
            y = newY;
        }
        return true;
    }

    // Mob.isBlocking: only a ground item exempts the full block of its own tile.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L351
    private boolean entityStepBlocked(int x, int y, int bit, int ex, int ey, boolean groundItem, LocalOverlay overlay) {
        int v = flags(x, y, overlay);
        return (v & bit) != 0
                || (v & (FULL_BLOCK_A | FULL_BLOCK_B)) != 0
                || ((v & FULL_BLOCK_C) != 0 && !(groundItem && x == ex && y == ey));
    }

    /** The landscape's ground texture byte for the tile; 0 off the map. */
    public int groundTexture(int x, int absY) {
        if (!inBounds(x, absY)) {
            return 0;
        }
        return groundTexture[absY * WIDTH + x] & 0xFF;
    }

    /**
     * Whether the tile lies in one of the landscape's sectors. A tile of none has no ground, and only
     * a sector's overlay or a loc ever fully blocks one that has.
     */
    public boolean inSector(int x, int absY) {
        if (!inBounds(x, absY)) {
            return false;
        }
        int idx = absY * WIDTH + x;
        return groundOverlay[idx] != 0
                || groundTexture[idx] != 0
                || (flags[idx] & ~objectFlags[idx] & FULL_BLOCK_C) == 0;
    }

    /** The tile's ground overlay, a 1-based tile def id, 0 for none or off the map; 250 reads as 2. */
    public int groundOverlay(int x, int absY) {
        if (!inBounds(x, absY)) {
            return 0;
        }
        return groundOverlay[absY * WIDTH + x] & 0xFF;
    }

    // One tile of WorldLoader.loadSection, except that a generic free door is not stamped.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L362
    private void stampTile(
            int bx,
            int by,
            int texture,
            int groundOverlay,
            int roofTex,
            int horizontalWall,
            int verticalWall,
            int diagonalWalls,
            DoorDefs doorDefs,
            TileDefs tileDefs) {
        int rawOverlay = groundOverlay;
        if (groundOverlay
                == 250) { // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L380
            groundOverlay = 2;
        }
        int idx = by * WIDTH + bx;
        flags[idx] = 0;
        this.groundTexture[idx] = (byte) texture;
        this.groundOverlay[idx] = (byte) groundOverlay;
        this.roofTexture[idx] = (byte) roofTex;
        if (groundOverlay > 0 && tileDefs.objectType(groundOverlay - 1) != 0) {
            or(bx, by, FULL_BLOCK_C);
        }
        if (verticalWall > 0 && doorDefs.collides(verticalWall - 1)) {
            if (!doorDefs.isGenericFreeDoor(verticalWall - 1)) {
                or(bx, by, WALL_NORTH);
                if (by - 1 >= 0) {
                    or(bx, by - 1, WALL_SOUTH);
                }
            }
            if (isLandscapeWallProjectilePass(verticalWall)) {
                setProjectileAllowed(bx, by);
                setProjectileAllowed(bx, by - 1);
            }
        }
        if (horizontalWall > 0 && doorDefs.collides(horizontalWall - 1)) {
            if (!doorDefs.isGenericFreeDoor(horizontalWall - 1)) {
                or(bx, by, WALL_EAST);
                if (bx - 1 >= 0) {
                    or(bx - 1, by, WALL_WEST);
                }
            }
            if (isLandscapeWallProjectilePass(horizontalWall)) {
                setProjectileAllowed(bx, by);
                setProjectileAllowed(bx - 1, by);
            }
        }
        int dw = diagonalWalls & 0xFFFF;
        if (dw > 0 && dw < 12000 && doorDefs.collides(dw - 1)) {
            if (!doorDefs.isGenericFreeDoor(dw - 1)) {
                or(bx, by, FULL_BLOCK_B);
            }
            if (isLandscapeWallProjectilePass(diagonalWalls & 0xFF)) {
                setProjectileAllowed(bx, by);
            }
        }
        if (dw > 12000 && dw < 24000 && doorDefs.collides(dw - 12001)) {
            if (!doorDefs.isGenericFreeDoor(dw - 12001)) {
                or(bx, by, FULL_BLOCK_A);
            }
            if (isLandscapeWallProjectilePass(diagonalWalls & 0xFF)) {
                setProjectileAllowed(bx, by);
            }
        }
        // The server tests the overlay it stored before turning 250 into 2.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L442
        if (rawOverlay == 2 || rawOverlay == 11) {
            setProjectileAllowed(bx, by);
        }
    }

    // A floor's last sector row runs 16 tiles into the next floor's rows, so without a wall a walk
    // could cross between floors.
    private void sealFloorSeams() {
        for (int floor = 1; floor < FLOOR_COUNT; floor++) {
            int seamY = floor * FLOOR_HEIGHT;
            for (int x = 0; x < WIDTH; x++) {
                or(x, seamY, WALL_NORTH);
                or(x, seamY - 1, WALL_SOUTH);
            }
        }
    }

    private void computeComponents() {
        int label = 0;
        int[] queue = new int[1 << 16];
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
                            if (!checkAdjacent(cx, cy, nx, ny, (LocalOverlay) null)) {
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

    // The projectile clip World.registerGameObject sets for static scenery.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L520
    private int applySceneryProjectileClip(List<SceneryLocs.Loc> scenery, ObjectDefs defs) {
        int stamped = 0;
        for (SceneryLocs.Loc loc : scenery) {
            ObjectDef def = defs.get(loc.id());
            if (def == null) {
                continue;
            }
            int objectType = def.typ();
            if (objectType != 1 && objectType != 2) {
                continue;
            }
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
                    stamped += setProjectileAllowed(x, y) ? 1 : 0;
                    if (objectType == 1) {
                        continue;
                    }
                    switch (dir) {
                        case 0 -> stamped += setProjectileAllowed(x - 1, y) ? 1 : 0;
                        case 2 -> stamped += setProjectileAllowed(x, y + 1) ? 1 : 0;
                        case 4 -> stamped += setProjectileAllowed(x + 1, y) ? 1 : 0;
                        case 6 -> stamped += setProjectileAllowed(x, y - 1) ? 1 : 0;
                        default -> {}
                    }
                }
            }
        }
        return stamped;
    }

    // The projectile clip World.registerGameObject sets for a listed boundary of door type 1: its tile
    // and, for dir 0 or 2, the neighbour that dir names as a scenery direction.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L593
    private int applyBoundaryProjectileClip(List<BoundaryLocs.Loc> boundaries, DoorDefs doorDefs) {
        int stamped = 0;
        for (BoundaryLocs.Loc b : boundaries) {
            int id = b.id();
            if (doorDefs.doorType(id) != 1) {
                continue;
            }
            if (!isNameProjectileClipAllowed(doorDefs.name(id))) {
                continue;
            }
            int x = b.pos().x();
            int y = b.pos().y();
            int dir = b.direction();
            stamped += setProjectileAllowed(x, y) ? 1 : 0;
            switch (dir) {
                case 0 -> stamped += setProjectileAllowed(x - 1, y) ? 1 : 0;
                case 2 -> stamped += setProjectileAllowed(x, y + 1) ? 1 : 0;
                case 4 -> stamped += setProjectileAllowed(x + 1, y) ? 1 : 0;
                case 6 -> stamped += setProjectileAllowed(x, y - 1) ? 1 : 0;
                default -> {}
            }
        }
        return stamped;
    }

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

    private void or(int x, int y, int mask) {
        flags[y * WIDTH + x] |= (byte) mask;
    }

    private void orObject(int x, int y, int mask) {
        int idx = y * WIDTH + x;
        flags[idx] |= (byte) mask;
        objectFlags[idx] |= (byte) mask;
    }

    private boolean destBlocked(int x, int y, int wallBits, LocalOverlay overlay) {
        int v = flags(x, y, overlay);
        return (v & wallBits) != 0 || (v & FULL_BLOCK) != 0;
    }

    // The diagonal arm of PathValidation.checkAdjacent.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L523
    private boolean canStepDiagonal(int fx, int fy, int tx, int ty, int from, LocalOverlay overlay, TileOccupancy mob) {
        boolean myX, newX;
        if (tx < fx) {
            myX = (from & WALL_EAST) != 0;
            newX = destBlocked(fx - 1, fy, WALL_WEST, overlay) || occupied(mob, fx - 1, fy);
        } else {
            myX = (from & WALL_WEST) != 0;
            newX = destBlocked(fx + 1, fy, WALL_EAST, overlay) || occupied(mob, fx + 1, fy);
        }
        boolean myY, newY;
        if (ty < fy) {
            myY = (from & WALL_NORTH) != 0;
            newY = destBlocked(fx, fy - 1, WALL_SOUTH, overlay) || occupied(mob, fx, fy - 1);
        } else {
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
        int cornerBits = (tx > fx ? WALL_EAST : WALL_WEST) | (ty > fy ? WALL_NORTH : WALL_SOUTH);
        if (destBlocked(tx, ty, cornerBits, overlay)) {
            return false;
        }
        return !diagonalPassThroughBlocked(fx, fy, tx, ty, overlay);
    }

    // PathValidation.checkDiagonalPassThroughCollisions, reading the tiles the server reads: its
    // north-west case reads (x + 1, y + 1) where the corner would be (x + 1, y - 1).
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L389
    private boolean diagonalPassThroughBlocked(int x, int y, int xn, int yn, LocalOverlay overlay) {
        if (xn == x - 1 && yn == y - 1) {
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
        if (xn == x + 1 && yn == y - 1) {
            int m = flags(x + 1, y, overlay);
            if ((m & (FULL_BLOCK_B | FULL_BLOCK_C)) != 0) {
                if ((flags(x, y - 1, overlay) & WALL_WEST) != 0) {
                    return true;
                }
                if ((flags(x + 1, y + 1, overlay) & WALL_EAST) != 0) {
                    return true;
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
        if (xn == x - 1 && yn == y + 1) {
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
        if (xn == x + 1 && yn == y + 1) {
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

    // PathValidation.checkAdjacentDistance: the reach and line-of-sight step, which unlike the walk
    // step can let a projectile-clip tile through and skips the diagonal pass-through test.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L118
    private boolean checkAdjacentDistance(
            int startX,
            int startY,
            int destX,
            int destY,
            boolean ignoreProjectileAllowed,
            boolean wantDiagCheck,
            LocalOverlay overlay) {
        boolean ipa = ignoreProjectileAllowed;
        int cx = startX, cy = startY;
        boolean myX = false, myY = false, newX = false, newY = false;
        if (startX > destX) {
            myX = checkBlockingDistance(startX, startY, WALL_EAST, true, ipa, overlay);
            newX = checkBlockingDistance(startX - 1, startY, WALL_WEST, false, ipa, overlay);
            cx = startX - 1;
        } else if (startX < destX) {
            myX = checkBlockingDistance(startX, startY, WALL_WEST, true, ipa, overlay);
            newX = checkBlockingDistance(startX + 1, startY, WALL_EAST, false, ipa, overlay);
            cx = startX + 1;
        }
        if (startY > destY) {
            myY = checkBlockingDistance(startX, startY, WALL_NORTH, true, ipa, overlay);
            newY = checkBlockingDistance(startX, startY - 1, WALL_SOUTH, false, ipa, overlay);
            cy = startY - 1;
        } else if (startY < destY) {
            myY = checkBlockingDistance(startX, startY, WALL_SOUTH, true, ipa, overlay);
            newY = checkBlockingDistance(startX, startY + 1, WALL_NORTH, false, ipa, overlay);
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
        if (cx > startX) {
            newX = checkBlockingDistance(cx, cy, WALL_EAST, false, ipa, overlay);
        } else if (cx < startX) {
            newX = checkBlockingDistance(cx, cy, WALL_WEST, false, ipa, overlay);
        }
        if (cy > startY) {
            newY = checkBlockingDistance(cx, cy, WALL_NORTH, false, ipa, overlay);
        } else if (cy < startY) {
            newY = checkBlockingDistance(cx, cy, WALL_SOUTH, false, ipa, overlay);
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
        boolean diagonalBlocked = false;
        if (startX + 1 == destX && startY + 1 == destY) {
            diagonalBlocked = checkBlockingDistance(
                    startX + 1, startY + 1, wantDiagCheck ? WALL_NORTH | WALL_EAST : -1, false, ipa, overlay);
        } else if (startX + 1 == destX && startY - 1 == destY) {
            diagonalBlocked = checkBlockingDistance(
                    startX + 1, startY - 1, wantDiagCheck ? WALL_SOUTH | WALL_EAST : -1, false, ipa, overlay);
        } else if (startX - 1 == destX && startY + 1 == destY) {
            diagonalBlocked = checkBlockingDistance(
                    startX - 1, startY + 1, wantDiagCheck ? WALL_NORTH | WALL_WEST : -1, false, ipa, overlay);
        } else if (startX - 1 == destX && startY - 1 == destY) {
            diagonalBlocked = checkBlockingDistance(
                    startX - 1, startY - 1, wantDiagCheck ? WALL_SOUTH | WALL_WEST : -1, false, ipa, overlay);
        }
        if (diagonalBlocked) {
            return false;
        }
        int xDiff = destX - startX, yDiff = destY - startY;
        if (Math.abs(xDiff) == 1 && Math.abs(yDiff) == 1) {
            if (checkBlockingDistance(startX + xDiff, startY, -2, false, ipa, overlay)
                    || checkBlockingDistance(startX, startY + yDiff, -2, false, ipa, overlay)) {
                return false;
            }
        }
        return true;
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L251
    private boolean checkBlockingDistance(
            int x, int y, int bit, boolean isCurrentTile, boolean ignoreProjectileAllowed, LocalOverlay overlay) {
        int f = flags(x, y, overlay);
        if (!ignoreProjectileAllowed && (projectileAllowed(x, y) || (f & PROJECTILE_PASS) != 0)) {
            return false;
        }
        return isBlockingMask(f, bit, isCurrentTile);
    }

    private static int[] grow(int[] a) {
        int[] b = new int[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static boolean isLandscapeWallProjectilePass(int rawWallId) {
        return PROJECTILE_CLIP_ALLOWED_WALL_IDS.contains(rawWallId);
    }

    /**
     * Whether the server gives a scenery def's tiles projectile clip: any 1x1 def but a tree or a
     * chest, and any def whose name is on the server's list.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L572">World.isProjectileClipAllowed</a>
     */
    public static boolean isSceneryProjectileClipAllowed(ObjectDef def) {
        String name = def.name() == null ? "" : def.name();
        if (!name.equalsIgnoreCase("tree")
                && def.width() == 1
                && def.height() == 1
                && !name.equalsIgnoreCase("chest")) {
            return true;
        }
        return isNameProjectileClipAllowed(name);
    }

    private static boolean isNameProjectileClipAllowed(String name) {
        return name != null && PROJECTILE_CLIP_ALLOWED_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    private static boolean occupied(TileOccupancy mob, int x, int y) {
        return mob != null && mob.blockedAt(x, y);
    }

    // PathValidation.isBlocking: bit -1 tests full blocks only, -2 only the diagonal ones.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L267
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

    public boolean canStep(int fx, int fy, int tx, int ty, Overlays o, TileOccupancy mob) {
        return canStep(fx, fy, tx, ty, o.walkOverlay(), mob);
    }

    public boolean canStep(int fx, int fy, int tx, int ty, Overlays o) {
        return canStep(fx, fy, tx, ty, o.walkOverlay(), null);
    }

    public boolean fullyBlocked(int x, int y, Overlays o) {
        return fullyBlocked(x, y, o.walkOverlay());
    }

    public boolean canReachMob(int fx, int fy, int tx, int ty, Overlays o) {
        return canReachMob(fx, fy, tx, ty, o.losOverlay());
    }

    public boolean canEngage(int fx, int fy, Targetable t, Overlays o) {
        return canEngage(fx, fy, t, o.losOverlay());
    }

    public boolean checkPath(int fromX, int fromY, int toX, int toY, Overlays o) {
        return checkPath(fromX, fromY, toX, toY, o.losOverlay());
    }

    public List<Point> engageTiles(Targetable t, Overlays o) {
        return engageTiles(t, o.losOverlay());
    }

    public List<Point> engageTilesAround(Rect footprint, Overlays o) {
        return engageTilesAround(footprint, o.losOverlay());
    }

    /**
     * {@code dest}, unless it is a {@link Point} or a {@link Rect} of at most 64 tiles every one of
     * which is fully blocked as built, such as a scenery's own footprint: then the
     * {@link #engageTilesAround} ring as one {@link Dest#anyOf}, or {@code null} when that ring is empty.
     */
    public Dest blockedGoalRing(Dest dest, Overlays o) {
        Rect rect = dest instanceof Rect r ? r : dest instanceof Point p ? Rect.of(p, p) : null;
        if (rect == null || !everyTileBlocked(rect)) {
            return dest;
        }
        List<Point> ring = engageTilesAround(rect, o);
        return ring.isEmpty() ? null : Dest.anyOf(ring.toArray(new Area[0]));
    }

    private boolean everyTileBlocked(Rect r) {
        if ((long) (r.maxX() - r.minX() + 1) * (r.maxY() - r.minY() + 1) > 64) {
            return false;
        }
        for (int y = r.minY(); y <= r.maxY(); y++) {
            for (int x = r.minX(); x <= r.maxX(); x++) {
                if (inBounds(x, y) && !fullyBlocked(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean resolvesFrom(int fx, int fy, int tx, int ty, int range, Overlays o) {
        return resolvesFrom(fx, fy, tx, ty, range, o.losOverlay());
    }

    public List<Point> rangeTiles(int tx, int ty, int range, Overlays o) {
        return rangeTiles(tx, ty, range, o.losOverlay());
    }
}
