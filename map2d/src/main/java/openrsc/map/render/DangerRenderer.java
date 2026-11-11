package openrsc.map.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.probe.NavDangerExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DangerRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(DangerRenderer.class);

    private static final int ALPHA_MIN = 100;

    private static final int ALPHA_MAX = 210;

    private DangerRenderer() {}

    public static BufferedImage render(int floor, NavDangerExport danger) {
        long t0 = System.nanoTime();
        int scale = TerrainRenderer.SCALE;
        int w = CollisionMap.WIDTH;
        int h = CollisionMap.FLOOR_HEIGHT;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
        double ceiling = danger.rawCeiling();
        int painted = 0;
        for (int zLocal = 0; zLocal < h; zLocal++) {
            int absZ = floorBase + zLocal;
            int pixelY = zLocal * scale;
            for (int x = 0; x < w; x++) {
                int v = danger.rawPenaltyAt(x, absZ);
                if (v <= 0) {
                    continue;
                }
                int argb = gradient(Math.min(1.0, v / ceiling));
                int pixelX = (w - 1 - x) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(pixelX + dx, pixelY + dy, argb);
                    }
                }
                painted++;
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info("Danger floor {} (cl {}): {} dangerous tiles ({} ms)", floor, danger.combatLevel(), painted, ms);
        return img;
    }

    public static byte[] renderPng(int floor, NavDangerExport danger) {
        BufferedImage img = render(floor, danger);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG encode failed for danger floor " + floor, e);
        }
    }

    private static int gradient(double t) {
        int a = (int) Math.round(ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * t);
        int g = (int) Math.round(150 * (1.0 - t));
        return (a << 24) | (0xFF << 16) | (g << 8);
    }
}
