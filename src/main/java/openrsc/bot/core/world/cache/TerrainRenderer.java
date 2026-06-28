package openrsc.bot.core.world.cache;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import openrsc.bot.core.defs.TileDefs;
import openrsc.bot.core.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-floor terrain raster — the biome/overlay colour layer that sits at the bottom of the web map.
 * Matches the stock client's minimap tile-colour generation at
 * {@code Client_Base/src/orsc/graphics/three/World.java:702-808} — every tile is a
 * {@link #SCALE}×{@link #SCALE} block of the colour the client would write at
 * {@code drawMinimapTile(x, z, ..., tileColour, ...)}.
 *
 * <p>Render path per tile:
 * <ul>
 *   <li>{@code groundOverlay > 0} → {@link TileDefs#colour(int)} decoded via
 *       the rules in {@code Scene.resourceToColor} (negative = packed 5-5-5
 *       RGB; positive = stock texture-archive index that we approximate).</li>
 *   <li>otherwise → 256-entry biome palette indexed by {@code groundTexture},
 *       mirroring {@code World.java:60-74}: 0-63 white→grey (water/snow),
 *       64-127 grass, 128-191 sand, 192-255 dirt.</li>
 * </ul>
 *
 * <p>Walls, diagonals, and blocked-tile fill live on a separate
 * {@code WallsRenderer} PNG so the user can toggle them. Resolution is 3px
 * per tile — same as the in-game minimap (96 tiles × 3 px = 288 px) — chosen
 * because walls need ≥1 pixel of width on tile edges and diagonals need 3
 * pixels to render the canonical staircase pattern.
 */
public final class TerrainRenderer {

  private static final Logger LOG = LoggerFactory.getLogger(TerrainRenderer.class);

  /**
   * Pixels per world tile. Matches the stock client minimap scale.
   */
  public static final int SCALE = 3;

  /**
   * 256-entry biome palette — packed 0xRRGGBB.
   */
  private static final int[] BIOME = buildBiomePalette();

  private TerrainRenderer() {
  }

  /**
   * Render the terrain layer for {@code floor} at {@link #SCALE} px/tile. Returned image is
   * {@code WIDTH*SCALE × FLOOR_HEIGHT*SCALE}, with world tile (0, 0) at the rightmost-3-pixels of
   * the top row (east mirrored to the right of the screen; see WallsRenderer for the matching
   * mirror).
   */
  public static BufferedImage render(int floor, CollisionMap cm, TileDefs tileDefs) {
    long t0 = System.nanoTime();
    int w = CollisionMap.WIDTH;
    int h = CollisionMap.FLOOR_HEIGHT;
    BufferedImage img = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int overlayCount = 0;
    for (int zLocal = 0; zLocal < h; zLocal++) {
      int absZ = floorBase + zLocal;
      int pixelY = zLocal * SCALE;
      for (int x = 0; x < w; x++) {
        int pixelX = (w - 1 - x) * SCALE;
        int rgb;
        int overlay = cm.groundOverlay(x, absZ) & 0xFF;
        if (overlay > 0) {
          rgb = overlayColor(overlay - 1, tileDefs);
          overlayCount++;
        } else {
          rgb = BIOME[cm.groundTexture(x, absZ) & 0xFF];
        }
        for (int dy = 0; dy < SCALE; dy++) {
          for (int dx = 0; dx < SCALE; dx++) {
            img.setRGB(pixelX + dx, pixelY + dy, rgb);
          }
        }
      }
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    LOG.info("Terrain floor {}: {}×{} px ({}× scale), {} overlay tiles ({} ms)",
        floor, w * SCALE, h * SCALE, SCALE, overlayCount, ms);
    return img;
  }

  /**
   * PNG-encode {@link #render}'s output.
   */
  public static byte[] renderPng(int floor, CollisionMap cm, TileDefs tileDefs) {
    BufferedImage img = render(floor, cm, tileDefs);
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ImageIO.write(img, "PNG", baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("PNG encode failed for floor " + floor, e);
    }
  }

  // ---- palette generators --------------------------------------------

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
   * Decode the minimap colour for a 0-based overlay tile def. The stock client's minimap rendering
   * is two-pass and the SECOND pass wins:
   *
   * <ol>
   *   <li>{@code World.java:574-581} (first pass, 3D-scene oriented) —
   *       forces {@code tileValue==4} to texture 1 (water) or texture 31
   *       (lava for {@code decorID==12}). This drives the 3D landscape
   *       face colour and the FIRST {@code drawMinimapTile} call.</li>
   *   <li>{@code World.java:706-723} (second pass, minimap-only) —
   *       re-draws every {@code tileValue==4} tile using the RAW
   *       {@code Colour} field as the texture id, overwriting whatever
   *       the first pass put down. So a bridge tile with
   *       {@code (Colour=3, TileValue=4)} ends up as PLANKS (brown) on
   *       the minimap, and a water tile with {@code (Colour=1, TileValue=4)}
   *       ends up as WATER (blue). The first-pass override never wins
   *       for the minimap.</li>
   * </ol>
   * <p>
   * For {@code tileValue==5} (bridges) the stock client inherits colour
   * from one of the four neighbour tiles ({@code World.java:583-609});
   * without that context we fall back to wood planks, which matches what
   * the bridge tile defs use in practice.
   * <p>
   * Everything else: negative {@code Colour} = packed 5-5-5 RGB
   * ({@code Scene.resourceToColor:393-409}), positive = texture id
   * resolved via the cartoon palette in {@link #toonscapeTexture}.
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
   * Cartoon-color approximation for each stock-client texture id. Source:
   * {@code rsc-c/src/custom/toonscape.c} — a hand-tuned mapping of the texture archive to flat
   * colours we can render without the binary texture pack. Anything not in this table falls back to
   * a neutral grey; add cases as you spot misrendered tiles.
   */
  private static int toonscapeTexture(int textureId) {
    return switch (textureId) {
      case 1 -> 0x5091FF;  // WATER
      case 2 -> 0x313131;  // WALL
      case 3 -> 0xA8530A;  // PLANKS
      case 6 -> 0x732A16;  // ROOF
      case 8 -> 0x007600;  // LEAFYTREE
      case 9 -> 0xA0591B;  // TREESTUMP
      case 11 -> 0x787A79;  // MOSSY
      case 15 -> 0xFFFFFF;  // MARBLE
      case 23 -> 0x333633;  // MOSSYBRICKS
      case 25 -> 0x365466;  // GUNGYWATER
      case 29 -> 0x5A2918;  // CAVERN
      case 30 -> 0x5A2918;  // CAVERN2
      case 31 -> 0xFF6F0C;  // LAVA
      case 36 -> 0xE6C69B;  // TENTBOTTOM
      case 37 -> 0x4A4A4A;  // CHAINMAIL2
      case 38 -> 0xDFDBC8;  // MUMMY
      case 48 -> 0x944B11;  // BARK
      case 49 -> 0xE6C69B;  // CANVAS
      default -> 0x808080;
    };
  }

  private static int rgb(int r, int g, int b) {
    return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
  }

  private static int clamp(int v) {
    return v < 0 ? 0 : v > 255 ? 255 : v;
  }
}
