package openrsc.map.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Composites the separate map layers into the flattened preview images and thumbnails. */
public final class MapPreview {

    /**
     * Paints {@code overlays} over {@code base} in order, each scaled bilinearly to the base's size, so
     * a layer drawn at more pixels per tile still registers, on white. Returns a new opaque image.
     */
    public static BufferedImage flatten(BufferedImage base, BufferedImage... overlays) {
        int w = base.getWidth();
        int h = base.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(base, 0, 0, null);
        for (BufferedImage overlay : overlays) {
            g.drawImage(overlay, 0, 0, w, h, null);
        }
        g.dispose();
        return out;
    }

    /**
     * Crops to the bounding box of every non-white pixel, padded by 8 px: white is the colour
     * {@link TerrainRenderer} gives ground texture 0. An all-white image is returned as it is.
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

    /** Scales down bilinearly to {@code targetWidth} px, keeping the aspect ratio; never scales up. */
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

    public static byte[] png(BufferedImage img) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        }
    }

    private MapPreview() {}
}
