package openrsc.map.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-floor walls / blocked-tiles raster — the ARGB overlay that sits on top of the
 * {@link TerrainRenderer} layer in the web UI. Bakes three things into one PNG (transparent where
 * there's no overlay), so the browser doesn't have to render tens of thousands of vector features:
 *
 * <ul>
 *   <li><b>Cardinal walls</b> (WALL_NORTH on owning tile, WALL_EAST on owning
 *       tile, WALL_SOUTH/WALL_WEST only at the world border) — a 1px line on
 *       the corresponding tile edge, colour {@code 0x606060} matching the
 *       stock minimap (mudclient {@code World.java:824-856}).</li>
 *   <li><b>Diagonal walls</b> (FULL_BLOCK_A = "\" / FULL_BLOCK_B = "/") — the
 *       canonical staircase, also from {@code World.java:858-885}.
 *       Orientation was verified against the in-game minimap; A is "\" and B
 *       is "/" in our north-up east-right render.</li>
 *   <li><b>Impassable tiles</b> (FULL_BLOCK_C) — semi-transparent dark fill
 *       so cliffs, lava, building interiors, and the like read as "you can't
 *       walk here" without drowning out the terrain colours. Water tiles
 *       (overlay id 12) are skipped: the terrain raster already paints them
 *       distinct blue, and including them would dump the whole ocean into
 *       this layer for no informational gain.</li>
 * </ul>
 *
 * <p>Output is {@code WIDTH*SCALE × FLOOR_HEIGHT*SCALE} {@link
 * BufferedImage#TYPE_INT_ARGB}, mirrored on X so east lands on the right of
 * the screen (same convention as {@link TerrainRenderer}). Every entry point takes an optional
 * {@code mult} — the {@code -Dmap.scale} multiplier — that enlarges the raster proportionally so a
 * scaled walls layer still registers against a scaled terrain layer.
 */
public final class WallsRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(WallsRenderer.class);

  public static final int SCALE = TerrainRenderer.SCALE;

  /**
   * Cardinal + diagonal walls. Matches stock client {@code wallColor = 6316128} at
   * {@code World.java:824}.
   */
  private static final int WALL_ARGB = 0xFF606060;
  /**
   * Semi-transparent dark fill for impassable tiles (cliffs, building interiors). Alpha ≈ 60/255
   * keeps the terrain colour visible.
   */
  private static final int BLOCK_ARGB = 0x60000000;
  /**
   * Fully transparent pixel — the default background.
   */
  private static final int CLEAR_ARGB = 0x00000000;

  /**
   * Water overlay id (0-based TileDefs index). Skipped from BLOCK_ARGB fill so we don't blanket the
   * open ocean.
   */
  private static final int WATER_OVERLAY_ID = 11;

  private WallsRenderer() {
  }

  /** Which ingredients to bake — the web map layers them on opposite sides of
   *  the scenery sprites: FILL below (a sprite explains why its tile is
   *  blocked), LINES above (building outlines stay continuous even where
   *  furniture legitimately touches a wall). */
  public enum Part { LINES, FILL, BOTH }

  /**
   * Pixels per tile for the standalone LINES part. The legacy combined raster
   * draws 1px lines at SCALE=3 on the interior-side pixel row — on screen
   * that's a bar covering a third of the room tile, visibly south/east of the
   * true edge (the door vectors are stroked ON the edge, which is how the
   * mismatch was noticed: furniture touching a wall "peeked out" past the
   * line). The LINES part renders at 6 px/tile with 2px strokes CENTRED on
   * the tile edge — same registration as the doors, half the visual weight.
   */
  public static final int LINES_SCALE = 6;

  public static BufferedImage render(int floor, CollisionMap cm) {
    return render(floor, cm, Part.BOTH, 1);
  }

  public static BufferedImage render(int floor, CollisionMap cm, Part part) {
    return render(floor, cm, part, 1);
  }

  /**
   * Render {@code part} at {@code mult}× the native px/tile (the {@code -Dmap.scale} multiplier).
   * {@code mult == 1} is the default output; higher values enlarge everything proportionally —
   * FILL/BOTH at {@code SCALE·mult}, LINES at {@code LINES_SCALE·mult} with {@code 2·mult}px strokes
   * — so a scaled walls layer still lines up with a scaled terrain layer.
   */
  public static BufferedImage render(int floor, CollisionMap cm, Part part, int mult) {
    int m = Math.max(1, mult);
    if (part == Part.LINES) {
      return renderLinesCentred(floor, cm, m);
    }
    long t0 = System.nanoTime();
    int s = SCALE * m;
    int w = CollisionMap.WIDTH;
    int h = CollisionMap.FLOOR_HEIGHT;
    int imgW = w * s;
    int imgH = h * s;
    BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
    // Init to transparent so we only have to set the overlay pixels.
    for (int y = 0; y < imgH; y++) {
      for (int x = 0; x < imgW; x++) {
        img.setRGB(x, y, CLEAR_ARGB);
      }
    }
    boolean lines = part != Part.FILL;
    boolean fills = part != Part.LINES;
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int wallCount = 0, diagCount = 0, blockCount = 0;
    for (int zLocal = 0; zLocal < h; zLocal++) {
      int absZ = floorBase + zLocal;
      int pixelY = zLocal * s;
      for (int x = 0; x < w; x++) {
        int pixelX = (w - 1 - x) * s;
        int f = cm.flags(x, absZ) & 0xFF;
        if (f == 0) {
          continue;
        }

        // FULL_BLOCK_C fill (skip water — already painted blue by
        // TerrainRenderer; flooding the ocean adds nothing).
        if (fills && (f & CollisionMap.FULL_BLOCK_C) != 0) {
          int overlay = cm.groundOverlay(x, absZ) & 0xFF;
          if (overlay - 1 != WATER_OVERLAY_ID) {
            fillBlock(img, pixelX, pixelY, s, BLOCK_ARGB);
            blockCount++;
          }
        }

        // Cardinal walls (owning tile only; world-border edge cases
        // for S/W handled with the same convention as the previous
        // vector exporter).
        if (!lines) {
          continue;
        }
        if ((f & CollisionMap.WALL_NORTH) != 0) {
          hline(img, pixelX, pixelY, s, WALL_ARGB);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_EAST) != 0) {
          // EAST = lower x in RSC. After our X mirror that's the
          // RIGHT side of the tile's pixel block, i.e., column
          // pixelX + s - 1.
          vline(img, pixelX + s - 1, pixelY, s, WALL_ARGB);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_SOUTH) != 0 && zLocal == h - 1) {
          hline(img, pixelX, pixelY + s - 1, s, WALL_ARGB);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_WEST) != 0 && x == w - 1) {
          vline(img, pixelX, pixelY, s, WALL_ARGB);
          wallCount++;
        }

        // Diagonals — the canonical staircase, one pixel per row across the
        // s×s block (a 3-pixel staircase at the native SCALE=3, wider when
        // scaled up).
        if ((f & CollisionMap.FULL_BLOCK_A) != 0) {
          // "\" — NW → SE
          for (int i = 0; i < s; i++) {
            img.setRGB(pixelX + i, pixelY + i, WALL_ARGB);
          }
          diagCount++;
        }
        if ((f & CollisionMap.FULL_BLOCK_B) != 0) {
          // "/" — SW → NE
          for (int i = 0; i < s; i++) {
            img.setRGB(pixelX + i, pixelY + s - 1 - i, WALL_ARGB);
          }
          diagCount++;
        }
      }
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    LOG.info("Walls floor {}: {}×{} px, {} walls, {} diagonals, {} block fills ({} ms)",
        floor, imgW, imgH, wallCount, diagCount, blockCount, ms);
    return img;
  }

  public static byte[] renderPng(int floor, CollisionMap cm) {
    return renderPng(floor, cm, Part.BOTH, 1);
  }

  public static byte[] renderPng(int floor, CollisionMap cm, Part part) {
    return renderPng(floor, cm, part, 1);
  }

  public static byte[] renderPng(int floor, CollisionMap cm, Part part, int mult) {
    BufferedImage img = render(floor, cm, part, mult);
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ImageIO.write(img, "PNG", baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("PNG encode failed for walls floor " + floor, e);
    }
  }

  /**
   * The LINES part at {@code LINES_SCALE·mult} px/tile: cardinal walls as {@code 2·mult}px strokes
   * straddling the tile edge ({@code mult}px each side — the same registration as the door vectors),
   * diagonals as {@code 2·mult}px-stroke corner-to-corner lines.
   */
  private static BufferedImage renderLinesCentred(int floor, CollisionMap cm, int mult) {
    long t0 = System.nanoTime();
    int w = CollisionMap.WIDTH;
    int h = CollisionMap.FLOOR_HEIGHT;
    int s = LINES_SCALE * mult;
    int stroke = 2 * mult;
    int half = mult; // stroke / 2
    BufferedImage img = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D g = img.createGraphics();
    g.setColor(new java.awt.Color(WALL_ARGB, true));
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int wallCount = 0;
    int diagCount = 0;
    for (int zLocal = 0; zLocal < h; zLocal++) {
      int absZ = floorBase + zLocal;
      for (int x = 0; x < w; x++) {
        int f = cm.flags(x, absZ) & 0xFF;
        if (f == 0) {
          continue;
        }
        // Tile pixel block after the X mirror: [px, px+s) x [py, py+s);
        // its NORTH edge is y=py, its EAST edge (RSC east = lower x,
        // mirrored to the right) is x=px+s.
        int px = (w - 1 - x) * s;
        int py = zLocal * s;
        if ((f & CollisionMap.WALL_NORTH) != 0) {
          g.fillRect(px, Math.max(0, py - half), s, py == 0 ? half : stroke);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_EAST) != 0) {
          g.fillRect(px + s - half, py, px + s >= w * s ? half : stroke, s);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_SOUTH) != 0 && zLocal == h - 1) {
          g.fillRect(px, h * s - half, s, half);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_WEST) != 0 && x == w - 1) {
          g.fillRect(0, py, half, s);
          wallCount++;
        }
        if ((f & CollisionMap.FULL_BLOCK_A) != 0) {
          // "\" — NW corner to SE corner of the tile block.
          drawDiag(g, px, py, px + s, py + s, stroke);
          diagCount++;
        }
        if ((f & CollisionMap.FULL_BLOCK_B) != 0) {
          // "/" — SW corner to NE corner.
          drawDiag(g, px, py + s, px + s, py, stroke);
          diagCount++;
        }
      }
    }
    g.dispose();
    long ms = (System.nanoTime() - t0) / 1_000_000;
    LOG.info("Wall lines floor {}: {}×{} px, {} walls, {} diagonals ({} ms)",
        floor, w * s, h * s, wallCount, diagCount, ms);
    return img;
  }

  private static void drawDiag(java.awt.Graphics2D g, int x1, int y1, int x2, int y2, int stroke) {
    java.awt.Stroke prev = g.getStroke();
    g.setStroke(new java.awt.BasicStroke(stroke));
    g.drawLine(x1, y1, x2, y2);
    g.setStroke(prev);
  }

  private static void fillBlock(BufferedImage img, int x0, int y0, int size, int argb) {
    for (int dy = 0; dy < size; dy++) {
      for (int dx = 0; dx < size; dx++) {
        img.setRGB(x0 + dx, y0 + dy, argb);
      }
    }
  }

  private static void hline(BufferedImage img, int x0, int y, int length, int argb) {
    for (int dx = 0; dx < length; dx++) {
      img.setRGB(x0 + dx, y, argb);
    }
  }

  private static void vline(BufferedImage img, int x, int y0, int length, int argb) {
    for (int dy = 0; dy < length; dy++) {
      img.setRGB(x, y0 + dy, argb);
    }
  }
}
