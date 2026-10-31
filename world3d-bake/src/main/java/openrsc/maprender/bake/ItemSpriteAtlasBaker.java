package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.ItemDef;
import com.openrsc.client.model.Sprite;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import openrsc.bot.render.WorldRenderer;
import orsc.graphics.two.HeadlessSurface;

/**
 * Bakes every item's ground sprite into {@code item-atlas.png} and {@code .json}: drawn as the client
 * draws an item on the ground, then cropped to its visible pixels. Each frame's {@code ax, ay} is
 * where the bottom centre of the client's draw box falls in the crop. Also writes the client's two
 * damage bubbles as {@code splat-red.png} and {@code splat-blue.png}, and projectiles 0 to 5 as
 * {@code projectile-N.png}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6116">mudclient.drawItemAt</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java#L432">EntityHandler.loadGUIParts</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java#L346">EntityHandler.loadProjectiles</a>
 */
public final class ItemSpriteAtlasBaker {

    private record ItemFrame(int id, int x, int y, int w, int h, int ax, int ay) {}

    private record Atlas(long baked, int width, int height, List<ItemFrame> frames) {}

    private record Composed(BufferedImage img, int ax, int ay) {}

    private static final int ATLAS_WIDTH = 2048;
    private static final int PADDING = 1;
    private static final int ORIGIN_X = 160;
    private static final int ORIGIN_Y = 32;
    // The size the client draws a ground item at.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L5152
    private static final int ITEM_W = 96;

    private static final int ITEM_H = 64;

    /** Rebakes only this atlas: {@code ItemSpriteAtlasBaker [outDir] [clientCacheDir]}. */
    public static void main(String[] args) throws Exception {
        WorldRenderer.configureCache(args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache");
        export(new File(args.length > 0 ? args[0] : "/tmp/item-atlas"), System.out::println);
    }

    /**
     * Reads the client defs, so {@link WorldRenderer#configureCache} must have run first.
     *
     * @throws IllegalStateException when the cache has no custom sprite archive
     */
    public static void export(File outDir, Consumer<String> log) throws Exception {
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
            rects.add(new int[] {penX, penY});
            penX += w + PADDING;
            shelfH = Math.max(shelfH, h);
        }
        int atlasH = penY + shelfH + PADDING;
        BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, atlasH, BufferedImage.TYPE_INT_ARGB);
        List<ItemFrame> meta = new ArrayList<>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            Frame f = frames.get(i);
            int[] r = rects.get(i);
            atlas.getGraphics().drawImage(f.img(), r[0], r[1], null);
            meta.add(new ItemFrame(
                    f.id(), r[0], r[1], f.img().getWidth(), f.img().getHeight(), f.ax(), f.ay()));
        }
        ImageIO.write(atlas, "png", new File(outDir, "item-atlas.png"));
        BakeJson.MAPPER.writeValue(
                new File(outDir, "item-atlas.json"), new Atlas(System.currentTimeMillis(), ATLAS_WIDTH, atlasH, meta));
        log.accept("item sprite atlas: " + frames.size() + " items, " + ATLAS_WIDTH + "x" + atlasH
                + (skipped > 0 ? ", skipped " + skipped : ""));

        bakeGuiSprite(surface, "GUI:8", new File(outDir, "splat-red.png"));
        bakeGuiSprite(surface, "GUI:9", new File(outDir, "splat-blue.png"));

        for (int i = 0; i <= 5; i++) {
            try {
                bakeGuiSprite(surface, "projectiles:" + i, new File(outDir, "projectile-" + i + ".png"));
            } catch (IllegalStateException e) {
                log.accept("projectile sprite " + i + " missing: " + e.getMessage());
            }
        }
    }

    private static void bakeGuiSprite(HeadlessSurface surface, String loc, File out) throws Exception {
        Sprite sprite = surface.spriteSelectCustomLoc(loc);
        if (sprite == null) {
            throw new IllegalStateException("GUI sprite missing: " + loc);
        }
        int w = sprite.getSomething1();
        int h = sprite.getSomething2();
        int[] passA = null;
        Arrays.fill(surface.pixelData, 0x000000);
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                passA = surface.pixelData.clone();
                Arrays.fill(surface.pixelData, 0xFFFFFF);
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

    private static Composed compose(HeadlessSurface surface, ItemDef def) {
        Sprite sprite = surface.spriteSelectCustomItem(def);
        if (sprite == null) {
            return null;
        }
        int[] passA = null;
        Arrays.fill(surface.pixelData, 0x000000);
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                passA = surface.pixelData.clone();
                Arrays.fill(surface.pixelData, 0xFFFFFF);
            }
            surface.drawSpriteClipping(
                    sprite,
                    ORIGIN_X,
                    ORIGIN_Y,
                    ITEM_W,
                    ITEM_H,
                    def.getPictureMask(),
                    0,
                    def.getBlueMask(),
                    false,
                    0,
                    1);
        }
        // Drawn over black, then over white: a pixel's alpha is 1 - (white - black) / 255 and its
        // colour the black pass divided by that, so black and translucent pixels both survive.
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

    private ItemSpriteAtlasBaker() {}
}
