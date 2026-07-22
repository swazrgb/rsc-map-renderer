package openrsc.gamedata.geometry;

/**
 * A concrete inclusive rectangle of world tiles — the multi-tile {@link Area}: a bank interior, a
 * hunt box, a city bounding box, the footprint a 2×2 scenery covers under its anchor {@link Point}.
 * (A single tile is a {@link Point}, which is also an {@code Area}.)
 *
 * <p>The rectangle is the closed range {@code [minX..maxX] × [minY..maxY]} — both corners are
 * <em>inside</em>, so a 1×1 rect has {@code minX == maxX}. The compact constructor normalises
 * swapped corners, so callers may pass them in any order. All region queries and rectangle algebra
 * (contains / intersects / grow / union / …) come from {@link Area}; this record adds only the
 * corner-bearing factories.
 *
 * <p>Field order is {@code (minX, minY, maxX, maxY)} — the two corners as {@code Point}s
 * ({@link #min()}, {@link #max()}). This mirrors the server's {@code Area} concept (a rectangle),
 * though the server orders its fields {@code (minX, maxX, minY, maxY)}.
 */
public record Rect(int minX, int minY, int maxX, int maxY) implements Area {

  /**
   * Normalises swapped corners so {@code min <= max} on both axes.
   */
  public Rect {
    if (minX > maxX) {
      int t = minX;
      minX = maxX;
      maxX = t;
    }
    if (minY > maxY) {
      int t = minY;
      minY = maxY;
      maxY = t;
    }
  }

  /**
   * Readability factory; identical to {@code new Rect(minX, minY, maxX, maxY)}.
   */
  public static Rect of(int minX, int minY, int maxX, int maxY) {
    return new Rect(minX, minY, maxX, maxY);
  }

  /**
   * The bounding box spanning two points (inclusive). Corner order does not matter.
   */
  public static Rect of(Point a, Point b) {
    return new Rect(a.x(), a.y(), b.x(), b.y());
  }

  /**
   * The 1×1 rectangle covering a single tile. (Usually you can just pass the {@link Point} itself,
   * since it is already an {@link Area}.)
   */
  public static Rect tile(Point p) {
    return new Rect(p.x(), p.y(), p.x(), p.y());
  }

  /**
   * The tile footprint a scenery covers, given its anchor, art {@code width × height} and facing
   * {@code dir}. Mirrors the OpenRSC server's {@code World.registerGameObject} placement and
   * {@code CollisionMap.applyScenery}: the footprint extends from the anchor toward {@code +x}/
   * {@code +y}, and <b>width/height swap when {@code dir} is not 0 or 4</b> (the object is rotated a
   * quarter turn). A 1×1 object yields a 1×1 rect regardless of {@code dir}.
   *
   * @param anchorX scenery origin tile x (e.g. {@code GameObject.x()})
   * @param anchorY scenery origin tile y (e.g. {@code GameObject.z()})
   * @param width   {@code ObjectDefs} art width
   * @param height  {@code ObjectDefs} art height
   * @param dir     facing 0/2/4/6
   */
  public static Rect footprint(int anchorX, int anchorY, int width, int height, int dir) {
    int w = width, h = height;
    if (dir != 0 && dir != 4) {
      w = height;
      h = width;
    }
    return new Rect(anchorX, anchorY, anchorX + w - 1, anchorY + h - 1);
  }

  /**
   * Already a rectangle — returns {@code this} rather than allocating.
   */
  @Override
  public Rect bounds() {
    return this;
  }

  @Override
  public String toString() {
    return "Rect[(" + minX + ", " + minY + ")..(" + maxX + ", " + maxY + ")]";
  }
}
