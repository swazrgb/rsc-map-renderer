package openrsc.gamedata.geometry;

import java.util.List;

/**
 * An immutable world tile coordinate — the bot's value type for "a spot in the OpenRSC world".
 * Replaces the scattered {@code static final int FOO_X / FOO_Z} constant pairs and the bare
 * {@code int[]{x, y}} tiles that litter scripts and the pathfinder.
 *
 * <h2>A point is a 1×1 {@link Area}</h2>
 * {@code Point} implements {@link Area} (its bounding box is itself: {@code minX == maxX == x},
 * {@code minY == maxY == y}), so anywhere an API takes an {@code Area} you may pass a point. That
 * inheritance brings the whole region surface for free — {@link #contains(Area)},
 * {@link #intersects(Area)}, {@link #chebyshev(Area)}, {@link #grow(int)}, {@link #tiles()},
 * {@link #center()}, … — with no extra fields stored. Point keeps only the genuinely
 * point-specific operations below (neighbours, compass steps, the view-circle metric).
 *
 * <h2>Axis naming: {@code y} is the second horizontal axis</h2>
 * This API uses the <b>server's</b> coordinate names: {@link #x} and {@link #y} are the two
 * horizontal world axes. The legacy script-facing API ({@link openrsc.bot.api.Bot#z()},
 * {@link openrsc.gamedata.api.Targetable#z()}, {@code GameObject.z()}, {@code Location.z}) calls this
 * same axis {@code z}. <b>{@code Point.y() == the legacy z()}.</b> They are the identical value;
 * only the letter differs. Neither is elevation — RSC is a 2.5D world and "height" is folded into
 * {@code y} as floor planes (see below). {@link openrsc.gamedata.world.CollisionMap} already names
 * this axis {@code y} internally, so {@code Point} aligns the value type with the server and the
 * collision layer; bridge methods on the script-facing types (e.g. {@code Targetable.point()})
 * convert their {@code z()} into a {@code Point}.
 *
 * <h2>Floors live in {@code y}</h2>
 * RSC encodes upper floors by offsetting {@code y} by {@link #FLOOR_HEIGHT} per floor: floor 0 is
 * ground, 1/2 are upper storeys, 3 is the basement, each {@code 944} tiles further along {@code y}.
 * {@link #floor()} and {@link #localY()} decompose this; {@link #sameFloor(Area)} guards the
 * distance helpers, which are only meaningful within one floor (two tiles on different floors are
 * hundreds of tiles apart in raw {@code y} even when stacked).
 *
 * <h2>Compass directions</h2>
 * RSC's map is mirrored on {@code x}: <b>east is {@code x-1}, west is {@code x+1}</b> (Varrock east
 * bank sits at a lower {@code x} than the west bank). On {@code y}, <b>south is {@code y+1}, north
 * is {@code y-1}</b>. {@link #north()} / {@link #south()} / {@link #east()} / {@link #west()}
 * encode this so callers never have to remember the mirroring.
 */
public record Point(int x, int y) implements Area, Comparable<Point> {

  /**
   * Per-floor {@code y} offset — RSC stores an upper-floor tile at {@code y + FLOOR_HEIGHT*floor}.
   * Mirrors {@code CollisionMap.FLOOR_HEIGHT} (pinned equal by {@code PointTest}); duplicated here
   * to keep this value type free of any collision-layer dependency.
   */
  public static final int FLOOR_HEIGHT = 944;

  /**
   * Readability factory; identical to {@code new Point(x, y)}.
   */
  public static Point of(int x, int y) {
    return new Point(x, y);
  }

  // ---- Area: a point is a 1×1 region ----

  @Override
  public int minX() {
    return x;
  }

  @Override
  public int minY() {
    return y;
  }

  @Override
  public int maxX() {
    return x;
  }

  @Override
  public int maxY() {
    return y;
  }

  // ---- floor decomposition ({@link Area#floor()} is inherited) ----

  /**
   * The {@code y} within this point's floor — {@code y} with the floor offset stripped.
   */
  public int localY() {
    return Math.floorMod(y, FLOOR_HEIGHT);
  }

  // ---- translation ----

  /**
   * This point shifted by {@code (dx, dy)}.
   */
  public Point translate(int dx, int dy) {
    return new Point(x + dx, y + dy);
  }

  /**
   * One tile north ({@code y-1}). See class doc for RSC compass orientation.
   */
  public Point north() {
    return new Point(x, y - 1);
  }

  /**
   * One tile south ({@code y+1}).
   */
  public Point south() {
    return new Point(x, y + 1);
  }

  /**
   * One tile east ({@code x-1} — the RSC map is mirrored on {@code x}).
   */
  public Point east() {
    return new Point(x - 1, y);
  }

  /**
   * One tile west ({@code x+1}).
   */
  public Point west() {
    return new Point(x + 1, y);
  }

  // ---- point-specific distance ({@link Area#chebyshev(Area)} is inherited) ----

  /**
   * Chebyshev (king-move / 8-direction) distance between two raw tile coordinates — the max of the
   * per-axis deltas, and the RSC metric for "how many steps apart" / "within N tiles". This is the
   * loose-coordinate companion to the instance {@link Area#chebyshev(Area)}: use it when you hold bare
   * {@code x()/z()} pairs (a mob, an object, a packed tile) and don't want to allocate a {@link Point}
   * just to measure. Meaningful only within one floor (see the class doc on {@code y}). The canonical
   * home for the {@code Math.max(Math.abs(...), Math.abs(...))} idiom — prefer it over re-inlining.
   */
  public static int chebyshev(int ax, int ay, int bx, int by) {
    return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
  }

  /**
   * Squared Euclidean distance, {@code dx*dx + dy*dy} (integer, no {@code sqrt}). The one place RSC
   * measures a <em>circle</em> rather than a Chebyshev square: the server's ~15-tile view radius is
   * {@code dx*dx + dz*dz <= 255} (the client's view-radius test), which drives
   * view-edge NPC spawn/despawn. Compare this against a squared threshold; never take the root.
   */
  public long euclideanSq(Point o) {
    long dx = x - o.x, dy = y - o.y;
    return dx * dx + dy * dy;
  }

  /**
   * 8-direction adjacency: Chebyshev distance exactly 1 (a king move away, not the same tile). This
   * is a pure geometry test — it ignores walls. For "can I actually step/act there" use the
   * wall-aware {@code CollisionMap.canStep / canEngage / canInteract}.
   */
  public boolean isAdjacent(Point o) {
    return !equals(o) && chebyshev(o) <= 1;
  }

  /**
   * 4-direction (orthogonal) adjacency — directly N/E/S/W, the only spots a wall-gated interaction
   * ({@code canInteract}) can fire from. True iff the tiles differ on exactly one axis by exactly
   * one ({@code |dx| + |dy| == 1}).
   */
  public boolean isCardinallyAdjacent(Point o) {
    return Math.abs(x - o.x) + Math.abs(y - o.y) == 1;
  }

  // ---- neighbours (pure geometry; ignore walls) ----

  /**
   * The four orthogonal neighbours — north, east, south, west.
   */
  public List<Point> cardinals() {
    return List.of(north(), east(), south(), west());
  }

  /**
   * The four diagonal neighbours — NE, SE, SW, NW.
   */
  public List<Point> diagonals() {
    return List.of(
        new Point(x - 1, y - 1), // NE (east = x-1, north = y-1)
        new Point(x - 1, y + 1), // SE
        new Point(x + 1, y + 1), // SW
        new Point(x + 1, y - 1)); // NW
  }

  /**
   * All eight surrounding tiles (cardinals then diagonals). Same set as the inherited
   * {@link #surroundingTiles()} for a single tile, but returned in a fixed compass order.
   */
  public List<Point> neighbours() {
    return List.of(
        north(), east(), south(), west(),
        new Point(x - 1, y - 1), new Point(x - 1, y + 1),
        new Point(x + 1, y + 1), new Point(x + 1, y - 1));
  }

  @Override
  public int compareTo(Point o) {
    int c = Integer.compare(x, o.x);
    return c != 0 ? c : Integer.compare(y, o.y);
  }

  @Override
  public String toString() {
    return "(" + x + ", " + y + ")";
  }
}
