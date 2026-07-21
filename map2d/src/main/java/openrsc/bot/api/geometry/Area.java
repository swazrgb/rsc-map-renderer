package openrsc.bot.api.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A rectangular region of world tiles — the common supertype an API accepts when it wants "either a
 * single tile or a box". Its whole contract is the inclusive bounding rectangle
 * {@code [minX..maxX] × [minY..maxY]}; every region query and rectangle operation is a
 * {@code default} method derived from those four accessors, so the two implementors share one
 * implementation:
 * <ul>
 *   <li>{@link Point} — a 1×1 area ({@code minX == maxX}, {@code minY == maxY}). A point genuinely
 *       <em>is</em> an area; it adds no fields to play along.</li>
 *   <li>{@link Rect} — an arbitrary rectangle (banks, hunt boxes, the multi-tile footprint a 2×2
 *       scenery covers).</li>
 * </ul>
 *
 * <p>Write {@code f(Area target)} to accept either:
 * <pre>
 *   boolean atSpot   = zone.contains(bot.location());   // Rect.contains(Point)
 *   List&lt;Point&gt; ring = map.interactableTiles(furnaceFootprint, overlay); // accepts Point or Rect
 * </pre>
 *
 * <p>Axis names follow {@link Point} ({@code y} is the second horizontal axis, == the legacy script
 * API's {@code z}). Naming note: this mirrors the <em>server's</em> {@code Area} (a rectangle), not
 * its {@code Region} (a {@code RegionManager} map sector) — they are unrelated concepts.
 */
public sealed interface Area permits Point, Rect {

  /**
   * Lowest {@code x} tile in the region (inclusive).
   */
  int minX();

  /**
   * Lowest {@code y} tile in the region (inclusive).
   */
  int minY();

  /**
   * Highest {@code x} tile in the region (inclusive).
   */
  int maxX();

  /**
   * Highest {@code y} tile in the region (inclusive).
   */
  int maxY();

  // ---- dimensions ----

  /**
   * Tile span along {@code x} ({@code maxX - minX + 1}); 1 for a {@link Point}.
   */
  default int width() {
    return maxX() - minX() + 1;
  }

  /**
   * Tile span along {@code y} ({@code maxY - minY + 1}); 1 for a {@link Point}.
   */
  default int height() {
    return maxY() - minY() + 1;
  }

  /**
   * Total tiles in the region ({@code width * height}).
   */
  default int tileCount() {
    return width() * height();
  }

  /**
   * Floor plane of this region (derived from {@link #minY}). See {@link Point#floor()}.
   */
  default int floor() {
    return Math.floorDiv(minY(), Point.FLOOR_HEIGHT);
  }

  /**
   * True when {@code other} sits on the same floor plane as this region.
   */
  default boolean sameFloor(Area other) {
    return floor() == other.floor();
  }

  // ---- corners ----

  /**
   * The minimum (lower-{@code x}, lower-{@code y}) corner tile.
   */
  default Point min() {
    return new Point(minX(), minY());
  }

  /**
   * The maximum (higher-{@code x}, higher-{@code y}) corner tile.
   */
  default Point max() {
    return new Point(maxX(), maxY());
  }

  /**
   * The tile nearest the geometric centre (integer, biased toward the min corner on even spans).
   */
  default Point center() {
    return new Point((minX() + maxX()) / 2, (minY() + maxY()) / 2);
  }

  /**
   * This region as a concrete {@link Rect}. {@link Rect} returns itself; {@link Point} allocates a
   * 1×1 rectangle. Use when you need a typed rectangle handle rather than the {@code Area} view.
   */
  default Rect bounds() {
    return new Rect(minX(), minY(), maxX(), maxY());
  }

  // ---- containment & overlap ----

  /**
   * True if {@code (x, y)} lies within the inclusive bounds.
   */
  default boolean contains(int x, int y) {
    return x >= minX() && x <= maxX() && y >= minY() && y <= maxY();
  }

  /**
   * True if {@code other} is wholly inside this region. Subsumes "contains this point" — a
   * {@link Point} is a 1×1 area, so {@code rect.contains(point)} routes here.
   */
  default boolean contains(Area other) {
    return other.minX() >= minX() && other.maxX() <= maxX()
           && other.minY() >= minY() && other.maxY() <= maxY();
  }

  /**
   * True if this region and {@code other} share at least one tile.
   */
  default boolean intersects(Area other) {
    return minX() <= other.maxX() && maxX() >= other.minX()
           && minY() <= other.maxY() && maxY() >= other.minY();
  }

  // ---- distance / projection ----

  /**
   * Chebyshev gap to {@code other} — {@code 0} when they touch or overlap, otherwise the
   * tiles-apart distance between the nearest edges. For two {@link Point}s this is the plain
   * king-move distance; for a region and a point it's "how many tiles outside the box". Only
   * meaningful on the same floor (see {@link Point#chebyshev}).
   */
  default int chebyshev(Area other) {
    int dx = Math.max(0, Math.max(other.minX() - maxX(), minX() - other.maxX()));
    int dy = Math.max(0, Math.max(other.minY() - maxY(), minY() - other.maxY()));
    return Math.max(dx, dy);
  }

  /**
   * The tile of this region closest to {@code p} (== {@code p} when already inside) — {@code p}'s
   * coordinates clamped into the inclusive bounds.
   */
  default Point clamp(Point p) {
    return new Point(
        Math.max(minX(), Math.min(maxX(), p.x())),
        Math.max(minY(), Math.min(maxY(), p.y())));
  }

  // ---- rectangle algebra (always yields a Rect) ----

  /**
   * This region expanded {@code r} tiles on every side. Growing a {@link Point} by {@code r} yields
   * a {@code (2r+1)}-square box centred on it — the RSC idiom for a hunt / aggro / wander zone.
   */
  default Rect grow(int r) {
    return grow(r, r);
  }

  /**
   * This region expanded {@code rx} on the {@code x} axis and {@code ry} on the {@code y} axis.
   */
  default Rect grow(int rx, int ry) {
    return new Rect(minX() - rx, minY() - ry, maxX() + rx, maxY() + ry);
  }

  /**
   * The smallest rectangle covering both this region and {@code p}.
   */
  default Rect expandToInclude(Point p) {
    return new Rect(
        Math.min(minX(), p.x()), Math.min(minY(), p.y()),
        Math.max(maxX(), p.x()), Math.max(maxY(), p.y()));
  }

  /**
   * The bounding rectangle enclosing both this region and {@code other} (not a set union — it fills
   * any gap between disjoint rectangles). {@code point.union(other)} gives the bounding box of the
   * two tiles.
   */
  default Rect union(Area other) {
    return new Rect(
        Math.min(minX(), other.minX()), Math.min(minY(), other.minY()),
        Math.max(maxX(), other.maxX()), Math.max(maxY(), other.maxY()));
  }

  /**
   * The overlapping region of this region and {@code other}, or {@code null} if they are disjoint.
   */
  default Rect intersection(Area other) {
    if (!intersects(other)) {
      return null;
    }
    return new Rect(
        Math.max(minX(), other.minX()), Math.max(minY(), other.minY()),
        Math.min(maxX(), other.maxX()), Math.min(maxY(), other.maxY()));
  }

  // ---- enumeration ----

  /**
   * Every tile in the region, row-major (ascending {@code y}, then {@code x}). A {@link Point}
   * yields a singleton list. Beware large rectangles — {@link #tileCount()} entries are allocated;
   * for per-tile work without the list, use {@link #forEach(Consumer)}.
   */
  default List<Point> tiles() {
    List<Point> out = new ArrayList<>(tileCount());
    for (int yy = minY(); yy <= maxY(); yy++) {
      for (int xx = minX(); xx <= maxX(); xx++) {
        out.add(new Point(xx, yy));
      }
    }
    return out;
  }

  /**
   * Visit every tile in the region without materialising a list.
   */
  default void forEach(Consumer<Point> action) {
    for (int yy = minY(); yy <= maxY(); yy++) {
      for (int xx = minX(); xx <= maxX(); xx++) {
        action.accept(new Point(xx, yy));
      }
    }
  }

  /**
   * Every tile immediately surrounding this region — the one-tile-thick ring just outside it
   * ({@link #grow(int) grow(1)} minus this region). For a {@link Point} that's its 8 neighbours; for
   * a {@link Rect} it's the full perimeter. Works the same regardless of size, so it's the candidate
   * set for "where could I stand to interact with this thing".
   *
   * <p>Pure geometry — it ignores walls and walkability, so a returned tile may be blocked or
   * separated from the region by a wall. To get only the tiles a click/use/talk actually fires
   * from, hand the region (this, a {@link Point}, or a {@link Rect}) to
   * {@code CollisionMap.interactableTiles(Area, WallOverlay)}, which filters this ring (plus, for wall sceneries,
   * the walkable interior) against the wall topology.
   */
  default List<Point> surroundingTiles() {
    int minX = minX(), minY = minY(), maxX = maxX(), maxY = maxY();
    List<Point> out = new ArrayList<>(2 * (width() + height()) + 4);
    for (int xx = minX - 1; xx <= maxX + 1; xx++) {
      out.add(new Point(xx, minY - 1));
      out.add(new Point(xx, maxY + 1));
    }
    for (int yy = minY; yy <= maxY; yy++) {
      out.add(new Point(minX - 1, yy));
      out.add(new Point(maxX + 1, yy));
    }
    return out;
  }
}
