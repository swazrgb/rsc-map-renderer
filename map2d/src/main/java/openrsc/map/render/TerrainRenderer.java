package openrsc.map.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import openrsc.gamedata.defs.TileDefs;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders a floor's terrain as the stock client's minimap colours it: one colour per tile, from the
 * tile's overlay when it has one and from its ground texture otherwise. The image is
 * {@code CollisionMap.WIDTH} by {@code FLOOR_HEIGHT} tiles, each a square of {@link #SCALE} times
 * {@code mult} px. Rows run north to south and x is mirrored, so east is on the right and tile
 * x = 0 is the rightmost column. A tile in no landscape sector is transparent; every other is opaque.
 */
public final class TerrainRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(TerrainRenderer.class);

    /**
     * Pixels per tile at {@code mult} 1, the stock minimap's.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L295">World.drawMinimapTile</a>
     */
    public static final int SCALE = 3;

    private static final int[] BIOME = buildBiomePalette();

    /**
     * Renders {@code floor} at {@code mult} times {@link #SCALE} px per tile; a {@code mult} below 1
     * counts as 1. With {@code dim} every channel is halved, the shading the client gives its minimap.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L298">World.drawMinimapTile</a>
     */
    public static BufferedImage render(int floor, CollisionMap cm, TileDefs tileDefs, boolean dim, int mult) {
        long t0 = System.nanoTime();
        int s = SCALE * Math.max(1, mult);
        int w = CollisionMap.WIDTH;
        int h = CollisionMap.FLOOR_HEIGHT;
        BufferedImage img = new BufferedImage(w * s, h * s, BufferedImage.TYPE_INT_ARGB);
        int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
        int overlayCount = 0;
        for (int zLocal = 0; zLocal < h; zLocal++) {
            int absZ = floorBase + zLocal;
            int pixelY = zLocal * s;
            for (int x = 0; x < w; x++) {
                int pixelX = (w - 1 - x) * s;
                int rgb;
                int overlay = cm.groundOverlay(x, absZ) & 0xFF;
                if (!cm.inSector(x, absZ)) {
                    continue;
                }
                if (overlay > 0) {
                    rgb = overlayColor(overlay - 1, tileDefs);
                    overlayCount++;
                } else {
                    rgb = BIOME[cm.groundTexture(x, absZ) & 0xFF];
                }
                if (dim) {
                    rgb = (rgb >> 1) & 0x7F7F7F;
                }
                rgb |= 0xFF000000;
                for (int dy = 0; dy < s; dy++) {
                    for (int dx = 0; dx < s; dx++) {
                        img.setRGB(pixelX + dx, pixelY + dy, rgb);
                    }
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info(
                "Terrain floor {}: {}×{} px ({}× scale), {} overlay tiles ({} ms)",
                floor,
                w * s,
                h * s,
                s,
                overlayCount,
                ms);
        return img;
    }

    public static byte[] renderPng(int floor, CollisionMap cm, TileDefs tileDefs) {
        return renderPng(floor, cm, tileDefs, false, 1);
    }

    public static byte[] renderPng(int floor, CollisionMap cm, TileDefs tileDefs, boolean dim, int mult) {
        BufferedImage img = render(floor, cm, tileDefs, dim, mult);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG encode failed for floor " + floor, e);
        }
    }

    // The client's four 64-step ground-texture ramps, kept at 8 bits per channel where the client
    // quantises them to 5.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L60
    private static int[] buildBiomePalette() {
        int[] p = new int[256];
        for (int i = 0; i < 64; i++) {
            p[i] = rgb(255 - i * 4, 255 - (int) (i * 1.75), 255 - i * 4);
        }
        for (int i = 0; i < 64; i++) {
            p[64 + i] = rgb(i * 3, 144, 0);
        }
        for (int i = 0; i < 64; i++) {
            p[128 + i] = rgb(192 - (int) (i * 1.5), 144 - (int) (i * 1.5), 0);
        }
        for (int i = 0; i < 64; i++) {
            p[192 + i] = rgb(96 - (int) (i * 1.5), (int) (i * 1.5) + 48, 0);
        }
        return p;
    }

    /**
     * The minimap colour of the 0-based overlay {@code id}: a negative colour is a packed 5-5-5 RGB,
     * any other a texture id. The client colours a tile with tile value 5 from a neighbour; this draws
     * it as planks.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/Scene.java#L401">Scene.resourceToColor</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L583">World.generateLandscapeModel</a>
     */
    private static int overlayColor(int id, TileDefs defs) {
        int colour = defs.colour(id);
        int tileValue = defs.tileValue(id);
        if (tileValue == 5) {
            return toonscapeTexture(3);
        }
        if (colour < 0) {
            int r = -(colour + 1);
            int r5 = (r >> 10) & 0x1F;
            int g5 = (r >> 5) & 0x1F;
            int b5 = r & 0x1F;
            return ((r5 << 3) << 16) | ((g5 << 3) << 8) | (b5 << 3);
        }
        return toonscapeTexture(colour);
    }

    /**
     * A flat colour for a client texture id, from rsc-c's ToonScape table; grey for a texture it lacks.
     *
     * @see <a href="https://gitlab.com/openrsc/rsc-c/-/blob/717c0dc93/src/custom/toonscape.c#L62">rsc-c apply_toonscape</a>
     */
    private static int toonscapeTexture(int textureId) {
        return switch (textureId) {
            case 1 -> 0x5091FF; // water
            case 2 -> 0x313131; // wall
            case 3 -> 0xA8530A; // planks
            case 6 -> 0x732A16; // roof
            case 8 -> 0x007600; // leafytree
            case 9 -> 0xA0591B; // treestump
            case 11 -> 0x787A79; // mossy
            case 15 -> 0xFFFFFF; // marble
            case 23 -> 0x333633; // mossybricks
            case 25 -> 0x365466; // gungywater
            case 29 -> 0x5A2918; // cavern
            case 30 -> 0x5A2918; // cavern2
            case 31 -> 0xFF6F0C; // lava
            case 36 -> 0xE6C69B; // tentbottom
            case 37 -> 0x4A4A4A; // chainmail2
            case 38 -> 0xDFDBC8; // mummy
            case 48 -> 0x944B11; // bark
            case 49 -> 0xE6C69B; // canvas
            default -> 0x808080;
        };
    }

    private static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    private TerrainRenderer() {}
}
