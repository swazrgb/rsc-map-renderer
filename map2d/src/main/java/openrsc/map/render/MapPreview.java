package openrsc.map.render;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Compositing helpers for the README/site showcase — flattening the terrain + walls layers into a
 * single ready-to-view map, cropping off the out-of-world void, and shrinking to a thumbnail. The
 * per-floor layers ({@link TerrainRenderer} / {@link WallsRenderer}) stay separate for the
 * interactive map; these produce the static preview images the docs embed.
 */
public final class MapPreview {

  private MapPreview() {
  }

  /**
   * Flatten one or more ARGB {@code overlays} over an opaque RGB {@code base}, painting them in
   * order (first = bottom) and bilinearly scaling each to the base's dimensions — an overlay that
   * renders at a different px/tile (the walls layer is 2× the terrain) is shrunk to register.
   * Returns a new opaque RGB image.
   */
  public static BufferedImage flatten(BufferedImage base, BufferedImage... overlays) {
    int w = base.getWidth();
    int h = base.getHeight();
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(base, 0, 0, null);
    for (BufferedImage overlay : overlays) {
      g.drawImage(overlay, 0, 0, w, h, null);
    }
    g.dispose();
    return out;
  }

  /**
   * Crop to the bounding box of the actual map. {@link TerrainRenderer} paints the out-of-world void
   * pure white, so the box is everything that isn't white, padded by an 8px margin. Returns the
   * input unchanged if it's entirely white (an empty floor).
   */
  public static BufferedImage cropToContent(BufferedImage img) {
    int w = img.getWidth();
    int h = img.getHeight();
    int[] px = img.getRGB(0, 0, w, h, null, 0, w);
    int minX = w;
    int minY = h;
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < h; y++) {
      int row = y * w;
      for (int x = 0; x < w; x++) {
        if ((px[row + x] & 0xFFFFFF) != 0xFFFFFF) {
          if (x < minX) {
            minX = x;
          }
          if (x > maxX) {
            maxX = x;
          }
          if (y < minY) {
            minY = y;
          }
          if (y > maxY) {
            maxY = y;
          }
        }
      }
    }
    if (maxX < 0) {
      return img;
    }
    int margin = 8;
    minX = Math.max(0, minX - margin);
    minY = Math.max(0, minY - margin);
    maxX = Math.min(w - 1, maxX + margin);
    maxY = Math.min(h - 1, maxY + margin);
    BufferedImage out = new BufferedImage(maxX - minX + 1, maxY - minY + 1, img.getType());
    Graphics2D g = out.createGraphics();
    g.drawImage(img, -minX, -minY, null);
    g.dispose();
    return out;
  }

  /** Bilinearly downscale to {@code targetWidth} px wide, preserving aspect ratio. */
  public static BufferedImage scaleToWidth(BufferedImage img, int targetWidth) {
    if (img.getWidth() <= targetWidth) {
      return img;
    }
    int targetHeight = Math.round((float) img.getHeight() * targetWidth / img.getWidth());
    BufferedImage out = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(img, 0, 0, targetWidth, targetHeight, null);
    g.dispose();
    return out;
  }

  /** PNG-encode to bytes. */
  public static byte[] png(BufferedImage img) throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ImageIO.write(img, "PNG", baos);
      return baos.toByteArray();
    }
  }
}
