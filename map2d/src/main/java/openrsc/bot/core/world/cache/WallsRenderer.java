package openrsc.bot.core.world.cache;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import openrsc.bot.core.world.CollisionMap;
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
 *   <li><b>Diagonal walls</b> (FULL_BLOCK_A = "/" / FULL_BLOCK_B = "\") — the
 *       canonical 3-pixel staircase, also from {@code World.java:858-885}.
 *       Orientation was verified against the in-game minimap; A is "/" and B
 *       is "\" in our north-up east-right render.</li>
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
 * the screen (same convention as {@link TerrainRenderer}).
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

  public static BufferedImage render(int floor, CollisionMap cm) {
    long t0 = System.nanoTime();
    int w = CollisionMap.WIDTH;
    int h = CollisionMap.FLOOR_HEIGHT;
    int imgW = w * SCALE;
    int imgH = h * SCALE;
    BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
    // Init to transparent so we only have to set the overlay pixels.
    for (int y = 0; y < imgH; y++) {
      for (int x = 0; x < imgW; x++) {
        img.setRGB(x, y, CLEAR_ARGB);
      }
    }
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int wallCount = 0, diagCount = 0, blockCount = 0;
    for (int zLocal = 0; zLocal < h; zLocal++) {
      int absZ = floorBase + zLocal;
      int pixelY = zLocal * SCALE;
      for (int x = 0; x < w; x++) {
        int pixelX = (w - 1 - x) * SCALE;
        int f = cm.flags(x, absZ) & 0xFF;
        if (f == 0) {
          continue;
        }

        // FULL_BLOCK_C fill (skip water — already painted blue by
        // TerrainRenderer; flooding the ocean adds nothing).
        if ((f & CollisionMap.FULL_BLOCK_C) != 0) {
          int overlay = cm.groundOverlay(x, absZ) & 0xFF;
          if (overlay - 1 != WATER_OVERLAY_ID) {
            fill3x3(img, pixelX, pixelY, BLOCK_ARGB);
            blockCount++;
          }
        }

        // Cardinal walls (owning tile only; world-border edge cases
        // for S/W handled with the same convention as the previous
        // vector exporter).
        if ((f & CollisionMap.WALL_NORTH) != 0) {
          hline(img, pixelX, pixelY, SCALE, WALL_ARGB);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_EAST) != 0) {
          // EAST = lower x in RSC. After our X mirror that's the
          // RIGHT side of the tile's pixel block, i.e., column
          // pixelX + SCALE - 1.
          vline(img, pixelX + SCALE - 1, pixelY, SCALE, WALL_ARGB);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_SOUTH) != 0 && zLocal == h - 1) {
          hline(img, pixelX, pixelY + SCALE - 1, SCALE, WALL_ARGB);
          wallCount++;
        }
        if ((f & CollisionMap.WALL_WEST) != 0 && x == w - 1) {
          vline(img, pixelX, pixelY, SCALE, WALL_ARGB);
          wallCount++;
        }

        // Diagonals — staircase pattern, three pixels each. SCALE
        // values other than 3 would need a different stride; the
        // minimap is 3 so we don't bother generalising.
        if ((f & CollisionMap.FULL_BLOCK_A) != 0) {
          // "\" — NW → SE: (0, 0), (1, 1), (2, 2)
          img.setRGB(pixelX, pixelY, WALL_ARGB);
          img.setRGB(pixelX + 1, pixelY + 1, WALL_ARGB);
          img.setRGB(pixelX + 2, pixelY + 2, WALL_ARGB);
          diagCount++;
        }
        if ((f & CollisionMap.FULL_BLOCK_B) != 0) {
          // "/" — SW → NE: (col 0, row 2), (1, 1), (2, 0)
          img.setRGB(pixelX, pixelY + 2, WALL_ARGB);
          img.setRGB(pixelX + 1, pixelY + 1, WALL_ARGB);
          img.setRGB(pixelX + 2, pixelY, WALL_ARGB);
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
    BufferedImage img = render(floor, cm);
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ImageIO.write(img, "PNG", baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("PNG encode failed for walls floor " + floor, e);
    }
  }

  private static void fill3x3(BufferedImage img, int x0, int y0, int argb) {
    for (int dy = 0; dy < SCALE; dy++) {
      for (int dx = 0; dx < SCALE; dx++) {
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
