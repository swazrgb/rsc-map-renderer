package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.extras.AnimationDef;
import com.openrsc.client.model.Sprite;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import openrsc.bot.render.WorldRenderer;
import orsc.graphics.two.HeadlessSurface;

/**
 * Bakes the sprite layers a player is composed from, without colours, so the viewer can colour and
 * stack them for any appearance: {@code atlas.png} and {@code index.json}.
 *
 * <p>Every animation of category {@code player} or {@code equipment} is drawn as the client draws a
 * player's layer, for orders 0 to 9 and 3 frames each, as {@link NpcSpriteAtlasBaker} numbers them.
 * Each pixel's alpha byte says how to colour it: 0 is empty, {@code 0xFF} a final colour in RGB; {@code 0xFE},
 * {@code 0xFD} and {@code 0xFC} take the hair, top and bottom colour, and {@code 0xFB} the skin
 * colour, each multiplied by the gray shade in RGB.
 *
 * <p>{@code index.json} maps animation id, then slot class, then {@code "order,walk"} to
 * {@code [uid, dx, dy]}: crop {@code uid} of the {@code atlas} table ({@code [x, y, w, h]}), placed
 * with its top-left at {@code (dx, dy)} on a surface where the draw box is {@code width1} by
 * {@code height} at {@code (originX, originY)}. Slot class 0 is any layer; 3 and 4 are the client's
 * layers 3 and 4, which it nudges when mirroring a walk, and are present only where they differ from
 * slot class 0.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6632">mudclient.drawPlayer</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6681">mudclient.drawPlayer</a>
 */
public final class PlayerLayerAtlasBaker {

    private record Tagged(int[] argb, int w, int h, int dx, int dy) {}

    private record Palettes(int[] hair, int[] clothing, int[] skin) {}

    private record Index(
            long baked,
            double scale,
            int originX,
            int originY,
            int width1,
            int height,
            Palettes palettes,
            int[][] layerOrder,
            int atlasW,
            int atlasH,
            List<int[]> atlas,
            Map<String, Map<String, Map<String, int[]>>> crops) {}

    private static final int[] HAIR = {
        0xffc030, 0xffa040, 0x805030, 0x604020, 0x303030, 0xff6020, 0xff4000, 0xffffff, 0x00ff00, 0x00ffff
    };
    private static final int[] CLOTHING = {
        0xff0000, 0xff8000, 0xffe000, 0xa0e000, 0x00e000, 0x008000, 0x00a080, 0x00b0ff, 0x0080ff, 0x0030f0, 0xe000e0,
        0x303030, 0x604000, 0x805000, 0xffffff
    };
    // The client's whole table: the five original colours, then the unlockable ones a server may send.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L150
    private static final int[] SKIN = {
        0xecded0, 0xccb366, 0xb38c40, 0x997326, 0x906020, 0x000000, 0x000004, 0x0066ff, 0x009000, 0x3cb371,
        0x55bfee, 0x55cfff, 0x604020, 0x663300, 0x6f5737, 0x705010, 0x804000, 0x996633, 0x999999, 0xac9e90,
        0xdcc399, 0xdccea0, 0xdcffd0, 0xdd3040, 0xeaded2, 0xeceed0, 0xecfed0, 0xecffd0, 0xfceee0, 0xff3333,
        0xff9f55, 0xffded2, 0xfffef0, 0xffffff, 0x00a0a0, 0xffff00, 0xff69b4, 0x0180a2, 0x86668e, 0x663399,
        0xb5ff1d, 0xa0c0c0, 0x608080
    };

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

    private static final int T_HAIR = 0xFE, T_TOP = 0xFD, T_BOTTOM = 0xFC, T_SKIN = 0xFB;
    // Two probe draws, the char, skin and blue masks set to rotated primaries: a char pixel comes out
    // red then green, skin green then blue, blue-masked blue then red, and an unmasked pixel the same
    // in both.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L1057
    private static final int A_CHAR = 0xFF0101, A_SKIN = 0x01FF01, A_BLUE = 0x0101FF;
    private static final int B_CHAR = 0x01FF01, B_SKIN = 0x0101FF, B_BLUE = 0xFF0101;

    private final HeadlessSurface surface;
    private final int width1 = Math.max(1, (int) Math.round(PLAYER_W * SCALE));
    private final int height = Math.max(1, (int) Math.round(PLAYER_H * SCALE));

    private final Map<Long, Integer> normalUid = new HashMap<>();
    private final Map<Long, Integer> normalPlace = new HashMap<>();

    private PlayerLayerAtlasBaker(HeadlessSurface surface) {
        this.surface = surface;
    }

    /** Rebakes only this atlas: {@code PlayerLayerAtlasBaker [outDir] [clientCacheDir]}. */
    public static void main(String[] args) throws Exception {
        String cache = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
        Path out = Path.of(args.length > 0 ? args[0] : "player-layers");
        export(cache, out, System.out::println);
    }

    /** @throws IOException when a file cannot be written or the cache has no custom sprite archive */
    public static void export(String clientCacheDir, Path outDir, Consumer<String> log) throws IOException {
        WorldRenderer.configureCache(clientCacheDir);
        HeadlessSurface s = new HeadlessSurface(SURF, SURF, 4501);
        if (!s.fillSpriteTree()) {
            throw new IOException("Custom_Sprites.osar missing");
        }
        new PlayerLayerAtlasBaker(s).run(outDir, log);
    }

    private void run(Path outDir, Consumer<String> log) throws IOException {
        Files.createDirectories(outDir);

        List<Tagged> unique = new ArrayList<>();
        Map<String, Integer> byHash = new HashMap<>();
        Map<String, Map<String, Map<String, int[]>>> crops = new LinkedHashMap<>();
        int animCount = EntityHandler.animationCount();
        int baked = 0;

        for (int animId = 0; animId < animCount; animId++) {
            AnimationDef anim = EntityHandler.getAnimationDef(animId);
            if (anim == null || !isWearable(anim)) {
                continue;
            }
            Map<String, Map<String, int[]>> slots = new LinkedHashMap<>();
            for (int slotClass : new int[] {0, 3, 4}) {
                Map<String, int[]> frames = new LinkedHashMap<>();
                for (int order = 0; order < 10; order++) {
                    for (int walk = 0; walk < 3; walk++) {
                        Tagged t = bakeLayer(anim, slotClass, order, walk);
                        if (t == null) {
                            continue;
                        }
                        String hash = hash(t);
                        Integer uid = byHash.get(hash);
                        // The hash only finds a candidate; a collision costs a duplicate crop, never a wrong one.
                        if (uid != null && !samePixels(unique.get(uid), t)) {
                            uid = null;
                        }
                        if (uid == null) {
                            uid = unique.size();
                            unique.add(t);
                            byHash.putIfAbsent(hash, uid);
                        }
                        if (slotClass != 0) {
                            Integer norm = normalUid.get(key(animId, 0, order, walk));
                            if (norm != null && norm.equals(uid) && samePlace(animId, order, walk, t.dx(), t.dy())) {
                                continue;
                            }
                        } else {
                            normalUid.put(key(animId, 0, order, walk), uid);
                            normalPlace.put(key(animId, 0, order, walk), (t.dx() << 16) | (t.dy() & 0xFFFF));
                        }
                        frames.put(order + "," + walk, new int[] {uid, t.dx(), t.dy()});
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

        List<int[]> atlasRegions = new ArrayList<>(unique.size());
        for (int i = 0; i < unique.size(); i++) {
            Tagged t = unique.get(i);
            atlasRegions.add(new int[] {regionX[i], regionY[i], t.w(), t.h()});
        }

        BakeJson.MAPPER.writeValue(
                outDir.resolve("index.json").toFile(),
                new Index(
                        System.currentTimeMillis(),
                        SCALE,
                        ORIGIN_X,
                        ORIGIN_Y,
                        width1,
                        height,
                        new Palettes(HAIR, CLOTHING, SKIN),
                        LAYER_ORDER,
                        atlasW,
                        Math.max(1, atlasH),
                        atlasRegions,
                        crops));
        log.accept("player layer atlas: " + baked + " frame-layers, " + unique.size() + " unique crops, atlas " + atlasW
                + "x" + Math.max(1, atlasH));
    }

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
                if (layerSlot == 4 && set == 1) {
                    variant = opposite;
                    ox = -22;
                    oy = -3;
                } else if (layerSlot == 4 && set == 2) {
                    variant = opposite;
                    ox = 0;
                    oy = -8;
                } else if (layerSlot == 4 && set == 3) {
                    variant = opposite;
                    ox = 26;
                    oy = -5;
                } else if (layerSlot == 3 && set == 1) {
                    variant = opposite;
                    ox = 22;
                    oy = 3;
                } else if (layerSlot == 3 && set == 2) {
                    variant = opposite;
                    ox = 0;
                    oy = 8;
                } else if (layerSlot == 3 && set == 3) {
                    variant = opposite;
                    ox = -26;
                    oy = 5;
                }
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

        Arrays.fill(surface.pixelData, 0);
        surface.drawSpriteClipping(sprite, drawX, drawY, spriteWidth, height, A_CHAR, A_SKIN, A_BLUE, flip, 0, 1);
        int[] probeA = surface.pixelData.clone();
        Arrays.fill(surface.pixelData, 0);
        surface.drawSpriteClipping(sprite, drawX, drawY, spriteWidth, height, B_CHAR, B_SKIN, B_BLUE, flip, 0, 1);
        int[] probeB = surface.pixelData;

        int cc = anim.getCharColour();
        boolean equipmentFixed = cc < 1 || cc > 3;
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

    private boolean samePlace(int animId, int order, int walk, int dx, int dy) {
        Integer p = normalPlace.get(key(animId, 0, order, walk));
        return p != null && p == ((dx << 16) | (dy & 0xFFFF));
    }

    private static long key(int animId, int slot, int order, int walk) {
        return ((long) animId << 20) | (slot << 8) | (order << 4) | walk;
    }

    private static int classify(int a, int b, boolean equipmentFixed, int charFixed, int blueMask) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        if (a == b) {
            return 0xFF000000 | (a & 0xFFFFFF);
        }
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
            return 0xFF000000 | mul(blueMask == 0 ? 0xFFFFFF : blueMask, ab);
        }
        return 0xFF000000 | (a & 0xFFFFFF);
    }

    private static int mul(int colour, int shade) {
        int r = (((colour >> 16) & 255) * shade) / 255;
        int g = (((colour >> 8) & 255) * shade) / 255;
        int bl = ((colour & 255) * shade) / 255;
        return (r << 16) | (g << 8) | bl;
    }

    private static boolean samePixels(Tagged a, Tagged b) {
        return a.w() == b.w() && a.h() == b.h() && Arrays.equals(a.argb(), b.argb());
    }

    private static boolean isWearable(AnimationDef anim) {
        return "player".equals(anim.category) || "equipment".equals(anim.category);
    }

    private static String hash(Tagged t) {
        long h = 1469598103934665603L;
        h = (h ^ t.w()) * 1099511628211L;
        h = (h ^ t.h()) * 1099511628211L;
        for (int p : t.argb()) {
            h = (h ^ (p & 0xFFFFFFFFL)) * 1099511628211L;
        }
        return Long.toHexString(h);
    }
}
