package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.NPCDef;
import com.openrsc.client.entityhandling.defs.extras.AnimationDef;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.openrsc.client.model.Sprite;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import openrsc.gamedata.NpcLocs;
import orsc.graphics.two.HeadlessSurface;

/**
 * Bakes the NPC sprite atlas for the 3D viewer: for every NPC id that spawns
 * in the world, the 8 camera-relative facing ORDERS × 3 walk frames, composed
 * exactly like the client's {@code drawNPC} (12 layers in per-facing order,
 * per-layer colour remap from the def, horizontal flips for facings 5/6/7 —
 * baked in, because flipped facings also change the layer ORDER, so they are
 * not pure mirrors).
 *
 * <p>Frames are baked at 1 engine-unit per pixel of the def's scene-sprite
 * size (camera1 × camera2) — the same size the client hands the scene — and
 * cropped to their opaque box with a bottom-centre anchor.
 */
public final class NpcSpriteAtlasBaker {

  /** mudclient.animDirLayer_To_CharLayer. */
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

  /** 6144 wide keeps the atlas ≤ 8192 on BOTH axes (WebGL texture cap on
   * older GPUs/swiftshader) now that combat frames pushed 4096-wide past
   * 9000px tall — an oversize atlas gets silently downscaled by three.js. */
  private static final int ATLAS_WIDTH = 6144;
  private static final int PADDING = 1;
  private static final int ORIGIN_X = 160;
  private static final int ORIGIN_Y = 32;
  /** Bake scale (atlas px per engine unit). Full size blows past GPU texture
   * caps (4096×76k); 0.3 keeps frames ~2× oversampled at typical viewer zoom
   * and the whole atlas within 4096². Quad WORLD sizes in the meta stay
   * unscaled — the viewer is resolution-independent. */
  private static final double SCALE = 0.3;

  /**
   * Per-npc metadata for the remote-control menu + combat animation. {@code atk}/{@code lvl}
   * are present only for attackable npcs; {@code cmd1}/{@code cmd2} only when the def sets them.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record NpcMeta(int id, int w, int h, int cs, int ws,
                         Integer atk, Integer lvl, String cmd1, String cmd2, String name) {}

  /** One baked frame: atlas rect + bottom-centre anchor, keyed by (id, order, frame). */
  private record NpcFrame(int id, int o, int f, int x, int y, int w, int h, int ax, int ay) {}

  private record Atlas(long baked, double scale, int width, int height,
                       List<NpcMeta> npcs, List<NpcFrame> frames) {}

  public static void export(File outDir, java.util.function.Consumer<String> log)
      throws Exception {
    var conf = openrsc.gamedata.ServerConf.resolve();
    List<NpcLocs.Spawn> spawns = NpcLocs.load(conf.locs().resolve("NpcLocs.json"));
    Set<Integer> ids = new LinkedHashSet<>();
    for (NpcLocs.Spawn sp : spawns) {
      ids.add(sp.id());
    }

    HeadlessSurface surface = new HeadlessSurface(512, 512, 4501);
    if (!surface.fillSpriteTree()) {
      throw new IllegalStateException("Custom_Sprites.osar missing — npc frames unavailable");
    }

    record Frame(int id, int order, int frame, BufferedImage img, int ax, int ay) {}
    List<Frame> frames = new ArrayList<>();
    List<NpcMeta> meta = new ArrayList<>();
    int skipped = 0;

    for (int id : ids) {
      NPCDef def = EntityHandler.getNpcDef(id);
      if (def == null) {
        skipped++;
        continue;
      }
      boolean any = false;
      // Orders 0-7 = camera-relative walk facings; 8/9 = combat stance A/B
      // (camera-independent — the client always draws combat in profile).
      for (int order = 0; order < 10; order++) {
        for (int walk = 0; walk < 3; walk++) {
          Composed c = compose(surface, def, order, walk);
          if (c == null) {
            continue;
          }
          frames.add(new Frame(id, order, walk, c.img(), c.ax(), c.ay()));
          any = true;
        }
      }
      if (any) {
        // cs = combat animation speed (def combatModel): stance A steps every
        // (cs−1) client frames, B every cs. ws = walk animation speed (def
        // walkModel): walk frame advances every ws stepFrames. 20ms per frame.
        // atk/lvl/cmd1/cmd2 feed the remote-control context menu: lvl is the
        // client's displayed combat level ((str+att+def+hits)/4, mudclient npc
        // menu), cmd1/cmd2 the def's right-click commands (Pickpocket, Trade…).
        String cmd1 = def.getCommand1();
        String cmd2 = def.getCommand2();
        int lvl = (def.getStr() + def.getAtt() + def.getDef() + def.getHits()) / 4;
        meta.add(new NpcMeta(id, def.getCamera1(), def.getCamera2(),
            def.getCombatModel(), def.getWalkModel(),
            def.isAttackable() ? 1 : null,
            def.isAttackable() ? lvl : null,
            cmd1 == null || cmd1.isBlank() ? null : cmd1,
            cmd2 == null || cmd2.isBlank() ? null : cmd2,
            def.getName()));
      } else {
        skipped++;
      }
    }

    // Shelf-pack, tallest first for density.
    frames.sort((a, b) -> b.img().getHeight() - a.img().getHeight());
    int penX = PADDING;
    int penY = PADDING;
    int shelfH = 0;
    List<int[]> rects = new ArrayList<>(frames.size());
    for (Frame f : frames) {
      int w = f.img().getWidth();
      int h = f.img().getHeight();
      if (penX + w + PADDING > ATLAS_WIDTH) {
        penX = PADDING;
        penY += shelfH + PADDING;
        shelfH = 0;
      }
      rects.add(new int[]{penX, penY});
      penX += w + PADDING;
      shelfH = Math.max(shelfH, h);
    }
    int atlasH = penY + shelfH + PADDING;
    BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, atlasH, BufferedImage.TYPE_INT_ARGB);
    List<NpcFrame> frameMeta = new ArrayList<>(frames.size());
    for (int i = 0; i < frames.size(); i++) {
      Frame f = frames.get(i);
      int[] r = rects.get(i);
      atlas.getGraphics().drawImage(f.img(), r[0], r[1], null);
      frameMeta.add(new NpcFrame(f.id(), f.order(), f.frame(), r[0], r[1],
          f.img().getWidth(), f.img().getHeight(), f.ax(), f.ay()));
    }
    ImageIO.write(atlas, "png", new File(outDir, "npc-atlas.png"));
    BakeJson.MAPPER.writeValue(new File(outDir, "npc-atlas.json"),
        new Atlas(System.currentTimeMillis(), SCALE, ATLAS_WIDTH, atlasH, meta, frameMeta));
    log.accept("npc sprite atlas: " + meta.size() + " npcs, " + frames.size()
        + " frames, " + ATLAS_WIDTH + "x" + atlasH + (skipped > 0 ? ", skipped " + skipped : ""));
  }

  /** One cropped frame with its bottom-centre anchor (in crop coords). */
  private record Composed(BufferedImage img, int ax, int ay) {}

  /** Port of drawNPC's per-facing composition (walk frames + combat set 5). */
  private static Composed compose(HeadlessSurface surface, NPCDef def,
      int order, int walk) {
    int set = order;
    // Layer draw order row: same as the facing for walk; combat uses row 2
    // (drawNPC forces var11 = 2 for COMBAT_A/COMBAT_B).
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
    int width1 = Math.max(1, (int) Math.round(def.getCamera1() * SCALE));
    int height = Math.max(1, (int) Math.round(def.getCamera2() * SCALE));
    int variantBase = set * 3 + walk;

    // Two passes on different backgrounds: the blit only writes where the
    // sprite is opaque, so pixels IDENTICAL in both passes are real content —
    // including genuine black (black vests rendered as holes when background
    // was distinguished by value 0 alone).
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
      int animId = def.getSprite(layer);
      if (animId < 0) {
        continue;
      }
      AnimationDef anim = EntityHandler.getAnimationDef(animId);
      if (anim == null) {
        continue;
      }
      if (set == 5 && !anim.hasA()) {
        continue; // layer has no combat frames (drawNPC: var13 != 5 || hasA)
      }
      int variant = variantBase;
      if (flip && set >= 1 && set <= 3 && anim.hasF()) {
        variant += 15;
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
      int xOffset = -(spriteWidth - width1) / 2;
      int colorVariant = anim.getCharColour();
      int baseColor = 0;
      if (colorVariant == 1) {
        baseColor = def.getSkinColour();
        colorVariant = def.getHairColour();
      } else if (animId >= 230) {
        baseColor = def.getSkinColour();
      } else if (colorVariant == 2) {
        colorVariant = def.getTopColour();
        baseColor = def.getSkinColour();
      } else if (colorVariant == 3) {
        baseColor = def.getSkinColour();
        colorVariant = def.getBottomColour();
      }
      surface.drawSpriteClipping(sprite, ORIGIN_X + xOffset, ORIGIN_Y, spriteWidth, height,
          colorVariant, baseColor, 0, flip, 0, 1);
      drewAny = true;
      }
    }
    if (!drewAny || passA == null) {
      return null;
    }

    // Alpha recovery from the two backgrounds: over black c_b = a·C, over
    // white c_w = a·C + (1−a)·255, so a = 1 − (c_w − c_b)/255 per channel.
    // Fully opaque pixels (incl. genuine black) match across passes (a=1);
    // translucent sprites (ghosts, shadows) get their real alpha instead of
    // being dropped.
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

  public static void main(String[] args) throws Exception {
    // Standalone: needs EntityHandler loaded + cache dir configured.
    orsc.Config.F_CACHE_DIR = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
    orsc.Config.S_WANT_CUSTOM_SPRITES = false;
    com.openrsc.client.entityhandling.EntityHandler.load(false);
    export(new File(args.length > 0 ? args[0] : "/tmp/npc-atlas"), System.out::println);
  }

  private NpcSpriteAtlasBaker() {}
}
