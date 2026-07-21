package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.extras.AnimationDef;
import com.openrsc.client.model.Sprite;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import orsc.graphics.two.HeadlessSurface;

/**
 * On-demand player-sprite composition: given an appearance token (worn sprite
 * ids + colour indices, exactly as the server's appearance update carries
 * them), composes the 8 facing orders × 3 walk frames with the engine's
 * {@code drawPlayer} rules and caches the strip on disk keyed by token hash.
 *
 * <p>Player rules vs the NPC baker: layer ids arrive +1 on the wire (0 = empty
 * slot); colours are PALETTE INDICES (hair[10], clothing[15], skin[5] — values
 * from the client's player_*_colours tables); the skin colour is ALWAYS passed
 * as the secondary mask together with the animation's blueMask.
 */
public final class PlayerSpriteService {

  private static final Pattern TOKEN =
      Pattern.compile("[0-9]{1,4}(,[0-9]{1,4}){0,15}\\|[0-9]{1,3},[0-9]{1,3},[0-9]{1,3},[0-9]{1,3}");

  private static final int[] HAIR = {0xffc030, 0xffa040, 0x805030, 0x604020, 0x303030,
      0xff6020, 0xff4000, 0xffffff, 0x00ff00, 0x00ffff};
  private static final int[] CLOTHING = {0xff0000, 0xff8000, 0xffe000, 0xa0e000,
      0x00e000, 0x008000, 0x00a080, 0x00b0ff, 0x0080ff, 0x0030f0, 0xe000e0, 0x303030,
      0x604000, 0x805000, 0xffffff};
  private static final int[] SKIN = {0xecded0, 0xccb366, 0xb38c40, 0x997326, 0x906020};

  /** mudclient.animDirLayer_To_CharLayer (same as the NPC baker). */
  private static final int[][] LAYER_ORDER = {
      {11, 2, 9, 7, 1, 6, 10, 0, 5, 8, 3, 4},
      {11, 2, 9, 7, 1, 6, 10, 0, 5, 8, 3, 4},
      {11, 3, 2, 9, 7, 1, 6, 10, 0, 5, 8, 4},
      {3, 4, 2, 9, 7, 1, 6, 10, 8, 11, 0, 5},
      {3, 4, 2, 9, 7, 1, 6, 10, 8, 11, 0, 5},
      {4, 3, 2, 9, 7, 1, 6, 10, 8, 11, 0, 5},
      {11, 4, 2, 9, 7, 1, 6, 10, 0, 5, 8, 3},
      {11, 2, 9, 7, 1, 6, 10, 0, 5, 8, 4, 3},
  };

  private static final double SCALE = 0.3;
  private static final int PLAYER_W = 145;
  private static final int PLAYER_H = 220;
  private static final int ORIGIN_X = 160;
  private static final int ORIGIN_Y = 32;

  private final Path cacheDir;
  private final String clientCacheDir;
  private HeadlessSurface surface;

  public PlayerSpriteService(Path cacheDir) {
    this(cacheDir, null);
  }

  /**
   * @param cacheDir       where composed strips are cached
   * @param clientCacheDir the stock client cache (sprites + defs); seeds
   *                       {@code Config.F_CACHE_DIR} on lazy init so composition works even when
   *                       no bake ran in this JVM (the bake normally sets it via WorldRenderer)
   */
  public PlayerSpriteService(Path cacheDir, String clientCacheDir) {
    this.cacheDir = cacheDir;
    this.clientCacheDir = clientCacheDir;
  }

  public Path pngPath(String token) throws IOException {
    return ensure(token)[0];
  }

  public Path jsonPath(String token) throws IOException {
    return ensure(token)[1];
  }

  /** Compose (or reuse) the strip for a token; returns {png, json} paths. */
  private synchronized Path[] ensure(String token) throws IOException {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new IllegalArgumentException("bad appearance token");
    }
    // Version prefix busts cached strips when the frame layout or composition
    // changes (v2: combat orders; v3: stock mirrored-walk weapon/shield swap).
    String key = sha1("v3|" + token);
    Files.createDirectories(cacheDir);
    Path png = cacheDir.resolve(key + ".png");
    Path json = cacheDir.resolve(key + ".json");
    if (Files.isRegularFile(png) && Files.isRegularFile(json)) {
      return new Path[]{png, json};
    }
    compose(token, png, json);
    return new Path[]{png, json};
  }

  private void compose(String token, Path pngOut, Path jsonOut) throws IOException {
    if (surface == null) {
      if (clientCacheDir != null) {
        // Idempotent: the bake path (WorldRenderer) sets the same value.
        orsc.Config.F_CACHE_DIR = clientCacheDir;
      }
      // EntityHandler defs load once per JVM (WorldRenderer does the same).
      try {
        EntityHandler.load(false);
      } catch (RuntimeException ignored) {
        // already loaded
      }
      HeadlessSurface s = new HeadlessSurface(512, 512, 4501);
      if (!s.fillSpriteTree()) {
        throw new IOException("Custom_Sprites.osar missing");
      }
      surface = s;
    }
    String[] halves = token.split("\\|");
    String[] ls = halves[0].split(",");
    int[] layers = new int[12];
    for (int i = 0; i < 12; i++) {
      layers[i] = i < ls.length ? Integer.parseInt(ls[i]) : 0;
    }
    String[] cs = halves[1].split(",");
    int hair = pal(HAIR, Integer.parseInt(cs[0]));
    int top = pal(CLOTHING, Integer.parseInt(cs[1]));
    int bottom = pal(CLOTHING, Integer.parseInt(cs[2]));
    int skin = pal(SKIN, Integer.parseInt(cs[3]));

    record Frame(int order, int walk, BufferedImage img, int ax, int ay) {}
    List<Frame> frames = new ArrayList<>(30);
    // Orders 0-7 = camera-relative walk facings; 8/9 = combat stance A/B
    // (camera-independent — the client always draws combat in profile).
    for (int order = 0; order < 10; order++) {
      for (int walk = 0; walk < 3; walk++) {
        Composed c = composeFrame(layers, hair, top, bottom, skin, order, walk);
        if (c != null) {
          frames.add(new Frame(order, walk, c.img, c.ax, c.ay));
        }
      }
    }
    if (frames.isEmpty()) {
      throw new IOException("appearance composed to nothing");
    }
    // Simple grid: 10 columns (8 facings + combat A/B) × 3 rows (frames).
    int cw = 0;
    int ch = 0;
    for (Frame f : frames) {
      cw = Math.max(cw, f.img().getWidth());
      ch = Math.max(ch, f.img().getHeight());
    }
    BufferedImage strip = new BufferedImage(cw * 10, ch * 3, BufferedImage.TYPE_INT_ARGB);
    StringBuilder fj = new StringBuilder();
    for (Frame f : frames) {
      int x = f.order() * cw;
      int y = f.walk() * ch;
      strip.getGraphics().drawImage(f.img(), x, y, null);
      if (fj.length() > 0) {
        fj.append(',');
      }
      fj.append("{\"o\":").append(f.order()).append(",\"f\":").append(f.walk())
          .append(",\"x\":").append(x).append(",\"y\":").append(y)
          .append(",\"w\":").append(f.img().getWidth()).append(",\"h\":").append(f.img().getHeight())
          .append(",\"ax\":").append(f.ax()).append(",\"ay\":").append(f.ay()).append('}');
    }
    ByteArrayOutputStream png = new ByteArrayOutputStream(64 << 10);
    ImageIO.write(strip, "png", png);
    Files.write(pngOut, png.toByteArray());
    try (PrintWriter w = new PrintWriter(jsonOut.toFile(), StandardCharsets.UTF_8)) {
      w.print("{\"scale\":" + SCALE + ",\"width\":" + strip.getWidth()
          + ",\"height\":" + strip.getHeight() + ",\"frames\":[" + fj + "]}");
    }
  }

  private record Composed(BufferedImage img, int ax, int ay) {}

  private Composed composeFrame(int[] layers, int hair, int top, int bottom, int skin,
      int order, int walk) {
    int set = order;
    // Layer draw order row: same as the facing for walk; combat uses row 2
    // (drawPlayer forces wantedAnimDir = 2 for COMBAT_A/COMBAT_B).
    int layerRow = order;
    boolean flip = false;
    if (set == 5) {
      set = 3;
      flip = true;
    } else if (set == 6) {
      set = 2;
      flip = true;
    } else if (set == 7) {
      set = 1;
      flip = true;
    } else if (order == 8 || order == 9) {
      set = 5;                 // combat sprite variants 15-17
      flip = order == 9;       // COMBAT_B mirrors COMBAT_A
      layerRow = 2;
    }
    int width1 = Math.max(1, (int) Math.round(PLAYER_W * SCALE));
    int height = Math.max(1, (int) Math.round(PLAYER_H * SCALE));
    int variantBase = set * 3 + walk;

    int[] passA = null;
    java.util.Arrays.fill(surface.pixelData, 0x000000);
    boolean drewAny = false;
    for (int pass = 0; pass < 2; pass++) {
      if (pass == 1) {
        passA = surface.pixelData.clone();
        java.util.Arrays.fill(surface.pixelData, 0xFFFFFF);
      }
      drewAny = false;
      for (int i = 0; i < 12; i++) {
        int layer = LAYER_ORDER[layerRow][i];
        int animId = layers[layer] - 1; // wire is +1; 0 = empty slot
        if (animId < 0) {
          continue;
        }
        AnimationDef anim = EntityHandler.getAnimationDef(animId);
        if (anim == null) {
          continue;
        }
        int variant = variantBase;
        int ox = 0;
        int oy = 0;
        if (flip && set >= 1 && set <= 3) {
          if (anim.hasF()) {
            variant += 15;
          } else if (layer == 4 || layer == 3) {
            // Stock mirrored-walk special case (drawPlayer): weapon/shield
            // layers without f-variants use the OPPOSITE-phase walk frame
            // (WALK[(k+2)%4] = frame 2−f) plus fixed pixel nudges, so the
            // held item swaps to the visible hand when walking mirrored.
            int opposite = set * 3 + (2 - walk);
            if (layer == 4 && set == 1) {
              variant = opposite;
              ox = -22;
              oy = -3;
            } else if (layer == 4 && set == 2) {
              variant = opposite;
              ox = 0;
              oy = -8;
            } else if (layer == 4 && set == 3) {
              variant = opposite;
              ox = 26;
              oy = -5;
            } else if (layer == 3 && set == 1) {
              variant = opposite;
              ox = 22;
              oy = 3;
            } else if (layer == 3 && set == 2) {
              variant = opposite;
              ox = 0;
              oy = 8;
            } else if (layer == 3 && set == 3) {
              variant = opposite;
              ox = -26;
              oy = 5;
            }
          }
        }
        if (set == 5 && !anim.hasA()) {
          continue;
        }
        Sprite sprite = surface.spriteSelectCustom(anim, variant);
        Sprite base = surface.spriteSelectCustom(anim, 0);
        if (sprite == null || base == null) {
          continue;
        }
        int s1 = sprite.getSomething1();
        int s2 = sprite.getSomething2();
        int s3 = base.getSomething1();
        if (s1 == 0 || s2 == 0 || s3 == 0) {
          continue;
        }
        int spriteWidth = (s1 * width1) / s3;
        int xOffset = (ox * width1) / s1 - (spriteWidth - width1) / 2;
        int yOffset = (oy * height) / s2;
        int colorMask1 = anim.getCharColour();
        if (colorMask1 == 1) {
          colorMask1 = hair;
        } else if (colorMask1 == 2) {
          colorMask1 = top;
        } else if (colorMask1 == 3) {
          colorMask1 = bottom;
        }
        surface.drawSpriteClipping(sprite, ORIGIN_X + xOffset, ORIGIN_Y + yOffset, spriteWidth,
            height,
            colorMask1, skin, anim.getBlueMask(), flip, 0, 1);
        drewAny = true;
      }
    }
    if (!drewAny || passA == null) {
      return null;
    }
    int[] passB = surface.pixelData;
    int[] argb = new int[512 * 512];
    int minX = 512;
    int minY = 512;
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < 512; y++) {
      for (int x = 0; x < 512; x++) {
        int i = y * 512 + x;
        int b = passA[i];
        int w2 = passB[i];
        int dr = ((w2 >> 16) & 255) - ((b >> 16) & 255);
        int dg = ((w2 >> 8) & 255) - ((b >> 8) & 255);
        int db = (w2 & 255) - (b & 255);
        int a255 = 255 - (Math.max(0, dr) + Math.max(0, dg) + Math.max(0, db)) / 3;
        if (a255 < 10) {
          continue;
        }
        int r = Math.min(255, ((b >> 16) & 255) * 255 / a255);
        int g = Math.min(255, ((b >> 8) & 255) * 255 / a255);
        int bb = Math.min(255, (b & 255) * 255 / a255);
        argb[i] = (a255 << 24) | (r << 16) | (g << 8) | bb;
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
    if (maxX < 0) {
      return null;
    }
    int w = maxX - minX + 1;
    int h = maxY - minY + 1;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int p = argb[(minY + y) * 512 + (minX + x)];
        if (p != 0) {
          img.setRGB(x, y, p);
        }
      }
    }
    return new Composed(img, ORIGIN_X + width1 / 2 - minX, ORIGIN_Y + height - minY);
  }

  private static int pal(int[] table, int idx) {
    return table[Math.max(0, Math.min(table.length - 1, idx))];
  }

  private static String sha1(String s) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
