package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.ItemDef;
import com.openrsc.client.model.Sprite;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import orsc.graphics.two.HeadlessSurface;

/**
 * Bakes the ground-item sprite atlas: one frame per item id, composed exactly
 * like the client's {@code drawItemAt} (inventory sprite drawn at the stock
 * ground size 96×64 with the def's picture mask), with the same dual-background
 * alpha recovery as the character bakers. The 3D view billboards these at tile
 * centres, bottom-anchored at ground height — the stock ground-item render.
 */
public final class ItemSpriteAtlasBaker {

  private static final int ATLAS_WIDTH = 2048;
  private static final int PADDING = 1;
  private static final int ORIGIN_X = 160;
  private static final int ORIGIN_Y = 32;
  /** Stock ground draw size in engine units — baked 1:1 (items are small). */
  private static final int ITEM_W = 96;
  private static final int ITEM_H = 64;

  public static void export(File outDir, java.util.function.Consumer<String> log)
      throws Exception {
    HeadlessSurface surface = new HeadlessSurface(512, 512, 4501);
    if (!surface.fillSpriteTree()) {
      throw new IllegalStateException("Custom_Sprites.osar missing — item frames unavailable");
    }

    record Frame(int id, BufferedImage img, int ax, int ay) {}
    List<Frame> frames = new ArrayList<>();
    int skipped = 0;
    for (int id = 0; id < EntityHandler.itemCount(); id++) {
      ItemDef def = EntityHandler.getItemDef(id);
      if (def == null) {
        skipped++;
        continue;
      }
      Composed c = compose(surface, def);
      if (c == null) {
        skipped++;
        continue;
      }
      frames.add(new Frame(id, c.img(), c.ax(), c.ay()));
    }

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
    StringBuilder fj = new StringBuilder();
    for (int i = 0; i < frames.size(); i++) {
      Frame f = frames.get(i);
      int[] r = rects.get(i);
      atlas.getGraphics().drawImage(f.img(), r[0], r[1], null);
      if (fj.length() > 0) {
        fj.append(',');
      }
      fj.append("{\"id\":").append(f.id())
          .append(",\"x\":").append(r[0]).append(",\"y\":").append(r[1])
          .append(",\"w\":").append(f.img().getWidth()).append(",\"h\":").append(f.img().getHeight())
          .append(",\"ax\":").append(f.ax()).append(",\"ay\":").append(f.ay()).append('}');
    }
    ImageIO.write(atlas, "png", new File(outDir, "item-atlas.png"));
    try (PrintWriter w = new PrintWriter(new File(outDir, "item-atlas.json"), StandardCharsets.UTF_8)) {
      w.print("{\"baked\":" + System.currentTimeMillis()
          + ",\"width\":" + ATLAS_WIDTH + ",\"height\":" + atlasH
          + ",\"frames\":[" + fj + "]}");
    }
    log.accept("item sprite atlas: " + frames.size() + " items, " + ATLAS_WIDTH + "x" + atlasH
        + (skipped > 0 ? ", skipped " + skipped : ""));

    // Stock hit-splat bubbles (drawn 24×24 screen px over a struck character):
    // GUI:8 = red (players), GUI:9 = blue (npcs) — colour keys on TARGET kind.
    bakeGuiSprite(surface, "GUI:8", new File(outDir, "splat-red.png"));
    bakeGuiSprite(surface, "GUI:9", new File(outDir, "splat-blue.png"));

    // Combat projectiles (opcode 104/234 types 3/4 spriteType): 0 orb,
    // 1 magic, 2 ranged, 3 gnomeball, 4 skull, 5 spikeball.
    for (int i = 0; i <= 5; i++) {
      try {
        bakeGuiSprite(surface, "projectiles:" + i,
            new File(outDir, "projectile-" + i + ".png"));
      } catch (IllegalStateException e) {
        log.accept("projectile sprite " + i + " missing: " + e.getMessage());
      }
    }
  }

  /** Bake one GUI sprite at its native size with the usual alpha recovery. */
  private static void bakeGuiSprite(HeadlessSurface surface, String loc, File out)
      throws Exception {
    Sprite sprite = surface.spriteSelectCustomLoc(loc);
    if (sprite == null) {
      throw new IllegalStateException("GUI sprite missing: " + loc);
    }
    int w = sprite.getSomething1();
    int h = sprite.getSomething2();
    int[] passA = null;
    java.util.Arrays.fill(surface.pixelData, 0x000000);
    for (int pass = 0; pass < 2; pass++) {
      if (pass == 1) {
        passA = surface.pixelData.clone();
        java.util.Arrays.fill(surface.pixelData, 0xFFFFFF);
      }
      surface.drawSpriteClipping(sprite, ORIGIN_X, ORIGIN_Y, w, h, 0, 0, 0, false, 0, 1);
    }
    int[] passB = surface.pixelData;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int i = (ORIGIN_Y + y) * 512 + ORIGIN_X + x;
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
        img.setRGB(x, y, (a255 << 24) | (r << 16) | (g << 8) | bb);
      }
    }
    ImageIO.write(img, "png", out);
  }

  private record Composed(BufferedImage img, int ax, int ay) {}

  /** Port of drawItemAt with the character bakers' two-pass alpha recovery. */
  private static Composed compose(HeadlessSurface surface, ItemDef def) {
    Sprite sprite = surface.spriteSelectCustomItem(def);
    if (sprite == null) {
      return null;
    }
    int[] passA = null;
    java.util.Arrays.fill(surface.pixelData, 0x000000);
    for (int pass = 0; pass < 2; pass++) {
      if (pass == 1) {
        passA = surface.pixelData.clone();
        java.util.Arrays.fill(surface.pixelData, 0xFFFFFF);
      }
      surface.drawSpriteClipping(sprite, ORIGIN_X, ORIGIN_Y, ITEM_W, ITEM_H,
          def.getPictureMask(), 0, def.getBlueMask(), false, 0, 1);
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
    return new Composed(img, ORIGIN_X + ITEM_W / 2 - minX, ORIGIN_Y + ITEM_H - minY);
  }

  public static void main(String[] args) throws Exception {
    orsc.Config.F_CACHE_DIR = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
    orsc.Config.S_WANT_CUSTOM_SPRITES = false;
    com.openrsc.client.entityhandling.EntityHandler.load(false);
    export(new File(args.length > 0 ? args[0] : "/tmp/item-atlas"), System.out::println);
  }

  private ItemSpriteAtlasBaker() {}
}
