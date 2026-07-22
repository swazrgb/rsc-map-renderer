package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.extras.AnimationDef;
import com.openrsc.client.model.Sprite;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import orsc.graphics.two.HeadlessSurface;

/**
 * Bakes the per-layer sprite atlas that lets the <em>viewer</em> composite any
 * player appearance client-side, instead of pre-baking (or serving on demand) a
 * full strip per fixed appearance token.
 *
 * <p>For every wearable animation ({@code player} + {@code equipment}) and every
 * facing/frame the engine can draw, one layer sprite is rendered pre-placed and
 * pre-scaled exactly as {@code drawPlayer} would, then reduced to a
 * <b>recolour-neutral tagged crop</b>: each pixel is classified into the same
 * buckets the engine's recolour kernel uses — a primary/char mask (hair, top or
 * bottom for the 3 body anims; a constant baked colour for equipment), the skin
 * mask, the per-anim blue mask, or an untouched passthrough — and stored as
 * {@code (shade, category)} rather than a final colour. The viewer replays the
 * multiply {@code dest = maskColour * shade} at composite time, so an arbitrary
 * {@code layers|colours} token renders with no server and no pre-baked strip.
 *
 * <h2>Classification (unambiguous, two probes)</h2>
 * The kernel {@code plot_trans_scale_with_2_masks} picks a mask per source pixel:
 * gray {@code R==G==B} → mask1 (char); {@code R==255 && G==B} → mask2 (skin);
 * {@code R==G && B!=G} (non-white blue mask) → blue mask; else passthrough. We
 * render each layer twice with the three masks set to rotated primaries:
 * <pre> A: char=RED  skin=GREEN blue=BLUE     B: char=GREEN skin=BLUE  blue=RED </pre>
 * A char pixel is then {@code (s,0,0)} in A and {@code (0,s,0)} in B; skin is
 * {@code (0,s,0)}/{@code (0,0,s)}; blue is {@code (0,0,s)}/{@code (s,0,0)}; a
 * passthrough pixel is its source colour in <em>both</em>, so {@code A==B}
 * identifies it even when the source is coincidentally single-channel.
 *
 * <h2>Atlas encoding</h2>
 * One RGBA PNG. Alpha is the classic hard-edged 0/opaque, but the opaque byte
 * carries the recolour <b>category</b> ({@code 0xFF} fixed, {@code 0xFE/FD/FC}
 * hair/top/bottom, {@code 0xFB} skin); RGB is the final colour (fixed) or the
 * gray shade (variable). Pixels are read back with {@code getImageData} and
 * composited by hand, so the tag survives untouched.
 */
public final class PlayerLayerAtlasBaker {

  // Palette tables — the wire carries indices into these (matching the engine).
  private static final int[] HAIR = {0xffc030, 0xffa040, 0x805030, 0x604020, 0x303030,
      0xff6020, 0xff4000, 0xffffff, 0x00ff00, 0x00ffff};
  private static final int[] CLOTHING = {0xff0000, 0xff8000, 0xffe000, 0xa0e000,
      0x00e000, 0x008000, 0x00a080, 0x00b0ff, 0x0080ff, 0x0030f0, 0xe000e0, 0x303030,
      0x604000, 0x805000, 0xffffff};
  private static final int[] SKIN = {0xecded0, 0xccb366, 0xb38c40, 0x997326, 0x906020};

  /** mudclient.animDirLayer_To_CharLayer (draw order per facing row). */
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
  private static final int SURF = 512;

  // Category tags carried in the opaque alpha byte.
  private static final int T_FIXED = 0xFF, T_HAIR = 0xFE, T_TOP = 0xFD, T_BOTTOM = 0xFC, T_SKIN = 0xFB;

  // Probe masks (rotated primaries); 0x01 stands in for "black" (0 is remapped
  // to white by drawSpriteClipping, so we use the darkest non-zero primary).
  private static final int A_CHAR = 0xFF0101, A_SKIN = 0x01FF01, A_BLUE = 0x0101FF;
  private static final int B_CHAR = 0x01FF01, B_SKIN = 0x0101FF, B_BLUE = 0xFF0101;

  private final HeadlessSurface surface;
  private final int width1 = Math.max(1, (int) Math.round(PLAYER_W * SCALE));
  private final int height = Math.max(1, (int) Math.round(PLAYER_H * SCALE));

  private PlayerLayerAtlasBaker(HeadlessSurface surface) {
    this.surface = surface;
  }

  /** Dev entrypoint. args[0] = out dir (default player-layers); args[1] = client cache. */
  public static void main(String[] args) throws Exception {
    String cache = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
    Path out = Path.of(args.length > 0 ? args[0] : "player-layers");
    export(cache, out, System.out::println);
  }

  /** One reduced crop: tagged ARGB pixels + its bbox origin in the 512² frame. */
  private record Tagged(int[] argb, int w, int h, int dx, int dy) {}

  /** Recolour palettes the viewer indexes with the wire's colour indices. */
  private record Palettes(int[] hair, int[] clothing, int[] skin) {}

  /**
   * {@code index.json}: composition params + palettes + the shelf-packed atlas region table
   * ({@code uid -> [ax, ay, w, h]}) + the crop tree ({@code animId -> slotClass -> "order,walk"
   * -> [uid, dx, dy]}) the viewer replays to composite any appearance client-side.
   */
  private record Index(double scale, int originX, int originY, int width1, int height,
                       Palettes palettes, int[][] layerOrder, int atlasW, int atlasH,
                       List<int[]> atlas, Map<String, Map<String, Map<String, int[]>>> crops) {}

  public static void export(String clientCacheDir, Path outDir, java.util.function.Consumer<String> log)
      throws IOException {
    orsc.Config.F_CACHE_DIR = clientCacheDir;
    orsc.Config.S_WANT_CUSTOM_SPRITES = false;
    try {
      EntityHandler.load(false);
    } catch (RuntimeException ignored) {
      // already loaded
    }
    HeadlessSurface s = new HeadlessSurface(SURF, SURF, 4501);
    if (!s.fillSpriteTree()) {
      throw new IOException("Custom_Sprites.osar missing");
    }
    new PlayerLayerAtlasBaker(s).run(outDir, log);
  }

  private void run(Path outDir, java.util.function.Consumer<String> log) throws IOException {
    Files.createDirectories(outDir);

    // Shelf packer state + content-dedup (identical tagged pixels share a region).
    List<Tagged> unique = new ArrayList<>();
    Map<String, Integer> byHash = new HashMap<>();
    // crops: animId -> slotClass -> "order,walk" -> [uid, dx, dy]. LinkedHashMaps
    // keep bake order stable across runs (JSON key order carries no meaning to
    // the viewer, but stable output keeps diffs clean).
    Map<String, Map<String, Map<String, int[]>>> crops = new LinkedHashMap<>();
    int animCount = EntityHandler.animationCount();
    int baked = 0;

    for (int animId = 0; animId < animCount; animId++) {
      AnimationDef anim = EntityHandler.getAnimationDef(animId);
      if (anim == null || !isWearable(anim)) {
        continue;
      }
      // slotClass 0 = normal; 3 = shield; 4 = weapon (only 3/4 get the stock
      // mirrored-walk nudge, and only where it differs from normal).
      Map<String, Map<String, int[]>> slots = new LinkedHashMap<>();
      for (int slotClass : new int[]{0, 3, 4}) {
        Map<String, int[]> frames = new LinkedHashMap<>();
        for (int order = 0; order < 10; order++) {
          for (int walk = 0; walk < 3; walk++) {
            Tagged t = bakeLayer(anim, slotClass, order, walk);
            if (t == null) {
              continue;
            }
            String hash = hash(t);
            Integer uid = byHash.get(hash);
            if (uid == null) {
              uid = unique.size();
              unique.add(t);
              byHash.put(hash, uid);
            }
            if (slotClass != 0) {
              // Only emit a slot override when it differs from normal (dedup by
              // uid+placement): the viewer falls back to slotClass 0 otherwise.
              Integer norm = normalUid.get(key(animId, 0, order, walk));
              if (norm != null && norm.equals(uid)
                  && samePlace(animId, order, walk, t.dx(), t.dy())) {
                continue;
              }
            } else {
              normalUid.put(key(animId, 0, order, walk), uid);
              normalPlace.put(key(animId, 0, order, walk), (t.dx() << 16) | (t.dy() & 0xFFFF));
            }
            frames.put(order + "," + walk, new int[]{uid, t.dx(), t.dy()});
            baked++;
          }
        }
        if (!frames.isEmpty()) {
          slots.put(String.valueOf(slotClass), frames);
        }
      }
      if (!slots.isEmpty()) {
        crops.put(String.valueOf(animId), slots);
      }
    }

    // Shelf-pack the unique crops into one atlas.
    int atlasW = 2048;
    int[] regionX = new int[unique.size()];
    int[] regionY = new int[unique.size()];
    int penX = 0, penY = 0, rowH = 0;
    for (int i = 0; i < unique.size(); i++) {
      Tagged t = unique.get(i);
      if (penX + t.w() > atlasW) {
        penX = 0;
        penY += rowH;
        rowH = 0;
      }
      regionX[i] = penX;
      regionY[i] = penY;
      penX += t.w() + 1;
      rowH = Math.max(rowH, t.h() + 1);
    }
    int atlasH = penY + rowH;
    BufferedImage atlas = new BufferedImage(atlasW, Math.max(1, atlasH), BufferedImage.TYPE_INT_ARGB);
    for (int i = 0; i < unique.size(); i++) {
      Tagged t = unique.get(i);
      for (int y = 0; y < t.h(); y++) {
        for (int x = 0; x < t.w(); x++) {
          int p = t.argb()[y * t.w() + x];
          if (p != 0) {
            atlas.setRGB(regionX[i] + x, regionY[i] + y, p);
          }
        }
      }
    }
    ImageIO.write(atlas, "png", outDir.resolve("atlas.png").toFile());

    // Atlas region table: uid -> [ax, ay, w, h].
    List<int[]> atlasRegions = new ArrayList<>(unique.size());
    for (int i = 0; i < unique.size(); i++) {
      Tagged t = unique.get(i);
      atlasRegions.add(new int[]{regionX[i], regionY[i], t.w(), t.h()});
    }

    BakeJson.MAPPER.writeValue(outDir.resolve("index.json").toFile(),
        new Index(SCALE, ORIGIN_X, ORIGIN_Y, width1, height,
            new Palettes(HAIR, CLOTHING, SKIN), LAYER_ORDER, atlasW, Math.max(1, atlasH),
            atlasRegions, crops));
    log.accept("player layer atlas: " + baked + " frame-layers, " + unique.size()
        + " unique crops, atlas " + atlasW + "x" + Math.max(1, atlasH));
  }

  // Placement/dedup bookkeeping for slot-override elision.
  private final Map<Long, Integer> normalUid = new HashMap<>();
  private final Map<Long, Integer> normalPlace = new HashMap<>();

  private static long key(int animId, int slot, int order, int walk) {
    return ((long) animId << 20) | (slot << 8) | (order << 4) | walk;
  }

  private boolean samePlace(int animId, int order, int walk, int dx, int dy) {
    Integer p = normalPlace.get(key(animId, 0, order, walk));
    return p != null && p == ((dx << 16) | (dy & 0xFFFF));
  }

  /** Render one layer sprite pre-placed via two probes, reduce to a tagged crop. */
  private Tagged bakeLayer(AnimationDef anim, int layerSlot, int order, int walk) {
    int set = order;
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
      set = 5;
      flip = order == 9;
    }
    int variant = set * 3 + walk;
    int ox = 0;
    int oy = 0;
    if (flip && set >= 1 && set <= 3) {
      if (anim.hasF()) {
        variant += 15;
      } else if (layerSlot == 4 || layerSlot == 3) {
        int opposite = set * 3 + (2 - walk);
        if (layerSlot == 4 && set == 1) { variant = opposite; ox = -22; oy = -3; }
        else if (layerSlot == 4 && set == 2) { variant = opposite; ox = 0; oy = -8; }
        else if (layerSlot == 4 && set == 3) { variant = opposite; ox = 26; oy = -5; }
        else if (layerSlot == 3 && set == 1) { variant = opposite; ox = 22; oy = 3; }
        else if (layerSlot == 3 && set == 2) { variant = opposite; ox = 0; oy = 8; }
        else if (layerSlot == 3 && set == 3) { variant = opposite; ox = -26; oy = 5; }
      }
    }
    if (set == 5 && !anim.hasA()) {
      return null;
    }
    Sprite sprite = surface.spriteSelectCustom(anim, variant);
    Sprite base = surface.spriteSelectCustom(anim, 0);
    if (sprite == null || base == null) {
      return null;
    }
    int s1 = sprite.getSomething1();
    int s2 = sprite.getSomething2();
    int s3 = base.getSomething1();
    if (s1 == 0 || s2 == 0 || s3 == 0) {
      return null;
    }
    int spriteWidth = (s1 * width1) / s3;
    int xOffset = (ox * width1) / s1 - (spriteWidth - width1) / 2;
    int yOffset = (oy * height) / s2;
    int blue = anim.getBlueMask();
    int drawX = ORIGIN_X + xOffset;
    int drawY = ORIGIN_Y + yOffset;

    java.util.Arrays.fill(surface.pixelData, 0);
    surface.drawSpriteClipping(sprite, drawX, drawY, spriteWidth, height,
        A_CHAR, A_SKIN, A_BLUE, flip, 0, 1);
    int[] probeA = surface.pixelData.clone();
    java.util.Arrays.fill(surface.pixelData, 0);
    surface.drawSpriteClipping(sprite, drawX, drawY, spriteWidth, height,
        B_CHAR, B_SKIN, B_BLUE, flip, 0, 1);
    int[] probeB = surface.pixelData;

    // Classify + bbox.
    int cc = anim.getCharColour();
    boolean equipmentFixed = cc < 1 || cc > 3; // not 1/2/3 -> constant colour (0 == white)
    int charFixed = anim.getCharColour();
    int minX = SURF, minY = SURF, maxX = -1, maxY = -1;
    int[] tag = new int[SURF * SURF];
    for (int i = 0; i < SURF * SURF; i++) {
      int a = probeA[i];
      int b = probeB[i];
      if (a == 0 && b == 0) {
        continue;
      }
      int px = classify(a, b, equipmentFixed, charFixed, blue);
      if (px == 0) {
        continue;
      }
      tag[i] = px;
      int x = i % SURF, y = i / SURF;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
    if (maxX < 0) {
      return null;
    }
    int w = maxX - minX + 1, h = maxY - minY + 1;
    int[] argb = new int[w * h];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        argb[y * w + x] = tag[(minY + y) * SURF + (minX + x)];
      }
    }
    return new Tagged(argb, w, h, minX, minY);
  }

  /** Map a probe-A/probe-B pixel pair to a tagged ARGB value (0 = drop). */
  private static int classify(int a, int b, boolean equipmentFixed, int charFixed, int blueMask) {
    int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
    if (a == b) {
      // Passthrough: source colour shows through unchanged.
      return 0xFF000000 | (a & 0xFFFFFF);
    }
    // char = only R in A; skin = only G in A; blue = only B in A.
    if (ag == 0 && ab == 0 && ar > 0) {
      int shade = ar;
      if (equipmentFixed) {
        return 0xFF000000 | mul(charFixed == 0 ? 0xFFFFFF : charFixed, shade);
      }
      int tag = charFixed == 1 ? T_HAIR : charFixed == 2 ? T_TOP : T_BOTTOM;
      return (tag << 24) | (shade << 16) | (shade << 8) | shade;
    }
    if (ar == 0 && ab == 0 && ag > 0) {
      int shade = ag;
      return (T_SKIN << 24) | (shade << 16) | (shade << 8) | shade;
    }
    if (ar == 0 && ag == 0 && ab > 0) {
      // Blue mask is a per-anim constant -> resolve to a final fixed colour.
      return 0xFF000000 | mul(blueMask == 0 ? 0xFFFFFF : blueMask, ab);
    }
    // Unexpected multi-channel A that isn't passthrough: treat as fixed source.
    return 0xFF000000 | (a & 0xFFFFFF);
  }

  /** Per-channel multiply used by the recolour kernel: dest = colour * shade/255. */
  private static int mul(int colour, int shade) {
    int r = (((colour >> 16) & 255) * shade) / 255;
    int g = (((colour >> 8) & 255) * shade) / 255;
    int bl = ((colour & 255) * shade) / 255;
    return (r << 16) | (g << 8) | bl;
  }

  private static boolean isWearable(AnimationDef anim) {
    return "player".equals(anim.category) || "equipment".equals(anim.category);
  }

  private static String hash(Tagged t) {
    // Cheap content hash (FNV-1a over pixels + size); collisions only cost extra
    // atlas space, never correctness (we compare on this key alone).
    long h = 1469598103934665603L;
    h = (h ^ t.w()) * 1099511628211L;
    h = (h ^ t.h()) * 1099511628211L;
    for (int p : t.argb()) {
      h = (h ^ (p & 0xFFFFFFFFL)) * 1099511628211L;
    }
    return Long.toHexString(h);
  }
}
