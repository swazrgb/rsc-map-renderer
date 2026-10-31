package openrsc.map.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a floor's collision map as a transparent overlay for {@link TerrainRenderer}, in the same
 * orientation: north up, x mirrored so east is on the right. Cardinal walls are drawn on the tile
 * edge they block, {@code FULL_BLOCK_A} as a {@code \} diagonal, {@code FULL_BLOCK_B} as a
 * {@code /}, and fully blocked tiles as a translucent dark fill.
 */
public final class WallsRenderer {

    /** Which layer to render. */
    public enum Part {
        /** Walls and diagonals at {@link #LINES_SCALE}, strokes centred on the tile edge. */
        LINES,
        /** The blocked-tile fill only, at {@link #SCALE}. */
        FILL,
        /** Fill plus walls and diagonals at {@link #SCALE}, lines 1 px wide inside the tile. */
        BOTH
    }

    private static final Logger LOG = LoggerFactory.getLogger(WallsRenderer.class);

    public static final int SCALE = TerrainRenderer.SCALE;

    /**
     * Pixels per tile of {@link Part#LINES}: twice {@link #SCALE}, so a 2 px stroke straddles the edge,
     * where the GeoJSON door lines lie.
     */
    public static final int LINES_SCALE = 6;

    private static final int WALL_ARGB = 0xFF606060; // the client's wallColor,
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L824
    private static final int BLOCK_ARGB = 0x60000000;
    private static final int CLEAR_ARGB = 0x00000000;

    private static final int WATER_OVERLAY_ID = 11;

    /**
     * Renders {@code part} of {@code floor} at {@code mult} times its native pixels per tile, so it
     * registers with a terrain layer rendered at the same {@code mult}; {@link Part#LINES} strokes
     * widen with it. A {@code mult} below 1 counts as 1.
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
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                img.setRGB(x, y, CLEAR_ARGB);
            }
        }
        boolean lines = part != Part.FILL;
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

                if ((f & CollisionMap.FULL_BLOCK_C) != 0) {
                    int overlay = cm.groundOverlay(x, absZ) & 0xFF;
                    if (overlay - 1 != WATER_OVERLAY_ID) {
                        fillBlock(img, pixelX, pixelY, s, BLOCK_ARGB);
                        blockCount++;
                    }
                }

                if (!lines) {
                    continue;
                }
                // The server stamps every wall on both tiles it parts, so drawing north and east edges
                // covers it; south and west only for the floor's outer edge, whose other side is
                // off-map.
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L556
                if ((f & CollisionMap.WALL_NORTH) != 0) {
                    hline(img, pixelX, pixelY, s, WALL_ARGB);
                    wallCount++;
                }
                if ((f & CollisionMap.WALL_EAST) != 0) {
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

                if ((f & CollisionMap.FULL_BLOCK_A) != 0) {
                    for (int i = 0; i < s; i++) {
                        img.setRGB(pixelX + i, pixelY + i, WALL_ARGB);
                    }
                    diagCount++;
                }
                if ((f & CollisionMap.FULL_BLOCK_B) != 0) {
                    for (int i = 0; i < s; i++) {
                        img.setRGB(pixelX + i, pixelY + s - 1 - i, WALL_ARGB);
                    }
                    diagCount++;
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info(
                "Walls floor {}: {}×{} px, {} walls, {} diagonals, {} block fills ({} ms)",
                floor,
                imgW,
                imgH,
                wallCount,
                diagCount,
                blockCount,
                ms);
        return img;
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

    private static BufferedImage renderLinesCentred(int floor, CollisionMap cm, int mult) {
        long t0 = System.nanoTime();
        int w = CollisionMap.WIDTH;
        int h = CollisionMap.FLOOR_HEIGHT;
        int s = LINES_SCALE * mult;
        int stroke = 2 * mult;
        int half = mult;
        BufferedImage img = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(WALL_ARGB, true));
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
                    drawDiag(g, px, py, px + s, py + s, stroke);
                    diagCount++;
                }
                if ((f & CollisionMap.FULL_BLOCK_B) != 0) {
                    drawDiag(g, px, py + s, px + s, py, stroke);
                    diagCount++;
                }
            }
        }
        g.dispose();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info(
                "Wall lines floor {}: {}×{} px, {} walls, {} diagonals ({} ms)",
                floor,
                w * s,
                h * s,
                wallCount,
                diagCount,
                ms);
        return img;
    }

    private static void drawDiag(Graphics2D g, int x1, int y1, int x2, int y2, int stroke) {
        Stroke prev = g.getStroke();
        g.setStroke(new BasicStroke(stroke));
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

    private WallsRenderer() {}
}
