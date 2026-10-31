package orsc.graphics.two;

import com.openrsc.client.entityhandling.defs.ItemDef;
import com.openrsc.client.entityhandling.defs.extras.AnimationDef;
import com.openrsc.client.model.Sprite;
import com.openrsc.data.DataConversions;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import orsc.Config;
import orsc.graphics.two.SpriteArchive.*;
import orsc.util.FastMath;
import orsc.util.GenUtil;

public class GraphicsController {

    public enum SPRITE_LAYER {
        MINIMAP,
        WORLDMAP,
        SHOP
    }

    public boolean interlace = false;

    public int height2;
    public int[] pixelData;
    public int width2;
    public Sprite[] sprites;
    private int clipTop = 0;
    private int clipLeft = 0;
    private int clipRight = 0;
    private int clipBottom = 0;
    public Map<String, Map<String, Entry>> spriteTree = new HashMap<>();
    private ZipFile spriteArchive;

    GraphicsController(int var1, int var2, int var3) {
        try {
            this.clipBottom = var2;
            this.clipRight = var1;
            this.pixelData = new int[var1 * var2];
            this.height2 = var2;
            this.width2 = var1;
            try {
                if (!Config.S_WANT_CUSTOM_SPRITES) {
                    spriteArchive = new ZipFile(
                            Config.F_CACHE_DIR + File.separator + "video" + File.separator + "Authentic_Sprites.orsc");
                    sprites = new Sprite[var3];
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(1);
            }
        } catch (RuntimeException var7) {
            throw GenUtil.makeThrowable(var7, "ua.<init>(" + var1 + ',' + var2 + ',' + var3 + ')' + ')');
        }
    }

    public static void a(int var0, int[] var1, int var2, int[] var3, int var4, int var5, int var6, int var7) {
        try {

            if (var2 < 0) {
                var4 = var1[('\uffa7' & var0) >> 8];
                var5 <<= 2;
                var0 += var5;
                int var8 = var2 / 16;

                int var9;
                for (var9 = var8; var9 < 0; ++var9) {
                    var3[var6++] = var4 + FastMath.bitwiseAnd(0x7F7F7F, var3[var6] >> 1);
                    var3[var6++] = var4 + FastMath.bitwiseAnd(0x7F7F7F, var3[var6] >> 1);
                    var3[var6++] = var4 + (FastMath.bitwiseAnd(var3[var6], 0xFEFEFF) >> 1);
                    var3[var6++] = var4 + (FastMath.bitwiseAnd(0xFEFEFF, var3[var6]) >> 1);
                    var4 = var1[255 & var0 >> 8];
                    var0 += var5;
                    var3[var6++] = FastMath.bitwiseAnd(var3[var6] >> 1, 0x7F7F7F) + var4;
                    var3[var6++] = FastMath.bitwiseAnd(var3[var6] >> 1, 0x7F7F7F) + var4;
                    var3[var6++] = (FastMath.bitwiseAnd(0xFEFEFF, var3[var6]) >> 1) + var4;
                    var3[var6++] = FastMath.bitwiseAnd(var3[var6] >> 1, 0x7F7F7F) + var4;
                    var4 = var1[var0 >> 8 & 255];
                    var3[var6++] = (FastMath.bitwiseAnd(var3[var6], 0xFEFEFE) >> 1) + var4;
                    var0 += var5;
                    var3[var6++] = (FastMath.bitwiseAnd(0xFEFEFF, var3[var6]) >> 1) + var4;
                    var3[var6++] = var4 + FastMath.bitwiseAnd(var3[var6] >> 1, 0x7F7F7F);
                    var3[var6++] = var4 + FastMath.bitwiseAnd(0x7F7F7F, var3[var6] >> 1);
                    var4 = var1[('\uff16' & var0) >> 8];
                    var0 += var5;
                    var3[var6++] = (FastMath.bitwiseAnd(var3[var6], 0xFEFEFF) >> 1) + var4;
                    var3[var6++] = var4 + FastMath.bitwiseAnd(var3[var6] >> 1, 0x7F7F7F);
                    var3[var6++] = var4 + (FastMath.bitwiseAnd(var3[var6], 0xFEFEFF) >> 1);
                    var3[var6++] = var4 + (FastMath.bitwiseAnd(0xFEFEFF, var3[var6]) >> 1);
                    var4 = var1[var0 >> 8 & 255];
                    var0 += var5;
                }

                var8 = -(var2 % 16);

                for (var9 = var7; var8 > var9; ++var9) {
                    var3[var6++] = FastMath.bitwiseAnd(var3[var6] >> 1, 0x7F7F7F) + var4;
                    if ((3 & var9) == 3) {
                        var4 = var1[(var0 & '\uff38') >> 8];
                        var0 += var5;
                        var0 += var5;
                    }
                }
            }
        } catch (RuntimeException var10) {
            throw GenUtil.makeThrowable(
                    var10,
                    "ua.S(" + var0 + ',' + (var1 != null ? "{...}" : "null") + ',' + var2 + ','
                            + (var3 != null ? "{...}" : "null") + ',' + var4 + ',' + var5 + ',' + var6 + ',' + var7
                            + ')');
        }
    }

    /**
     * Custom-spritepack lookup by "category:name" location regardless of
     * {@code S_WANT_CUSTOM_SPRITES} — for headless GUI-sprite baking (splats,
     * skulls). Returns null when the sprite doesn't exist.
     */
    public Sprite spriteSelectCustomLoc(String spriteLocation) {
        try {
            String[] location = spriteLocation.split(":");
            if (location.length < 2) {
                return null;
            }
            var frames = spriteTree.get(location[0]).get(location[1]).getFrames();
            return frames.length < 1 || frames[0] == null ? null : frames[0].getSprite();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Custom-spritepack item lookup regardless of {@code S_WANT_CUSTOM_SPRITES}
     * — for headless item baking. Returns null when the sprite doesn't exist.
     */
    public Sprite spriteSelectCustomItem(ItemDef item) {
        try {
            String[] location = item.getSpriteLocation().split(":");
            if (location.length < 2) {
                return null;
            }
            var frames = spriteTree.get(location[0]).get(location[1]).getFrames();
            return frames.length < 1 || frames[0] == null ? null : frames[0].getSprite();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Custom-spritepack frame lookup regardless of {@code S_WANT_CUSTOM_SPRITES}
     * — for headless character baking, where the tree is loaded but the global
     * flag stays false. Returns null when the frame doesn't exist.
     */
    public Sprite spriteSelectCustom(AnimationDef animation, int offset) {
        try {
            var frames = spriteTree.get(animation.category).get(animation.name).getFrames();
            if (offset < 0 || offset >= frames.length) {
                return null;
            }
            var f = frames[offset];
            return f == null ? null : f.getSprite();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void drawEntity(int index, int x, int y, int width, int height, int var1, int var8) {
        try {
            Sprite sprite = sprites[index];
            this.drawSprite(sprite, x, y, width, height, 5924);

        } catch (RuntimeException var10) {
            throw GenUtil.makeThrowable(
                    var10,
                    "ua.B(" + var1 + ',' + index + ',' + height + ',' + x + ',' + y + ',' + width + ',' + 29 + ','
                            + var8 + ')');
        }
    }

    /**
     * destHeight is height / heightStep. Black is transparent.
     *
     * @param heightStep    destinationHeight / height
     * @param srcStartY     First source row << 16
     * @param destWidth     Destination width
     *
     *
     * @param scaleY        (source rows per destination row) << 16
     * @param srcWidth   columns in source data
     * @param scaleX        (source columns per destination column) << 16
     *
     * @param destHead      first destination pixel to output to
     * @param src           source pixel data
     * @param srcStartX     First source column << 16
     * @param destRowStride Pixels to skip between rows of output
     * @param dest          Destination pixel data
     */
    private void plot_scale_black_mask(
            int[] src,
            int heightStep,
            int scaleX,
            int dummy1,
            int srcStartY,
            int[] dest,
            byte dummy2,
            int scaleY,
            int destHeight,
            int srcStartX,
            int destRowStride,
            int destWidth,
            int srcWidth,
            int destHead) {
        try {

            try {
                int firstColumn = srcStartX;

                for (int i = -destHeight; i < 0; i += heightStep) {
                    int srcRowHead = (srcStartY >> 16) * srcWidth;
                    srcStartY += scaleY;

                    for (int j = -destWidth; j < 0; ++j) {
                        int color = src[(srcStartX >> 16) + srcRowHead];
                        srcStartX += scaleX;
                        if (color != 0) {
                            dest[destHead++] = color;
                        } else {
                            ++destHead;
                        }
                    }

                    destHead += destRowStride;
                    srcStartX = firstColumn;
                }
            } catch (Exception var20) {
                System.out.println("error in plot_scale");
            }

        } catch (RuntimeException var21) {
            throw GenUtil.makeThrowable(
                    var21,
                    "ua.DA(" + (src != null ? "{...}" : "null") + ',' + heightStep + ',' + scaleX + ',' + dummy1 + ','
                            + srcStartY + ',' + (dest != null ? "{...}" : "null") + ',' + dummy2 + ',' + scaleY + ','
                            + destHeight + ',' + srcStartX + ',' + destRowStride + ',' + destWidth + ',' + srcWidth
                            + ',' + destHead + ')');
        }
    }

    /*
     * public final void applyColorLookupTable(int imgID) { try {
     *  if (null != this.spriteColours[imgID]) { int
     * pixCount = this.image2D_width[imgID] * this.image2D_height[imgID]; byte[]
     * pixels = this.spriteColours[imgID]; int[] colorLookupTable =
     * this.image2D_colorLookupTable[imgID]; int[] outputPixels = new
     * int[pixCount];
     *
     * for (int i = 0; pixCount > i; ++i) { int color = colorLookupTable[255 &
     * pixels[i]]; if (color == 0) { color = 1; } else if (color == 16711935) {
     * color = 0; }
     *
     * outputPixels[i] = color; }
     *
     * this.surfaceSetPixels[imgID] = outputPixels; this.spriteColours[imgID] =
     * null; this.image2D_colorLookupTable[imgID] = null; } } catch
     * (RuntimeException var9) { throw GenUtil.makeThrowable(var9, "ua.P(" +
     * imgID + ',' + -342059728 + ')'); } }
     */

    /**
     * @param src                  source pixel data
     * @param mask1                background color to show through when the source data is [x,
     *                             x, x] (dest = background * source)
     * @param mask2                background color to show through when the source data is [255,
     *                             x, x] (dest = background * source)
     *
     * @param destColumnSkewPerRow increase in destination first column per destination row
     * @param srcWidth             width of source data
     * @param srcStartY            (source start row) << 16
     * @param scaleY               (source rows per destination row) << 16
     * @param destFirstColumn      first column to store to in the first destination row
     * @param scaleX               (source columns per destination row) << 16
     * @param dest                 destination pixel data
     * @param skipEveryOther       if this is 0 or 1 the rasterizer skips every other destination
     *                             pixel
     *
     * @param srcStartX            (source start column) << 16
     * @param destRowHead          pixel address of first column of the first row to store
     * @param destColumnCount      destination column count
     * @param destHeight           destination row count
     * @param colourTransform      The colour and opacity with which to shade this sprite a uniform colour
     */
    private void plot_trans_scale_with_2_masks(
            int[] dest,
            int[] src,
            int destColumnCount,
            int destColumnSkewPerRow,
            int destFirstColumn,
            int dummy1,
            int spritePixel,
            int mask2,
            int scaleY,
            int scaleX,
            int srcStartX,
            int skipEveryOther,
            int srcStartY,
            int srcWidth,
            int mask1,
            int destHeight,
            int destRowHead,
            int colourTransform,
            int blueMask) {
        try {

            int mask1R = mask1 >> 16 & 0xFF;
            int mask1G = mask1 >> 8 & 0xFF;
            int mask1B = mask1 & 0xFF;
            int mask2R = mask2 >> 16 & 0xFF;
            int mask2G = mask2 >> 8 & 0xFF;
            int mask2B = mask2 & 0xFF;

            if (dummy1 != 1603920392) {
                this.clipBottom = 29;
            }

            if (blueMask == 0) blueMask = 0xFFFFFF;

            try {
                int var27 = srcStartX;

                for (int var28 = -destHeight; var28 < 0; ++var28) {
                    int var29 = (srcStartY >> 16) * srcWidth;
                    int var30 = destFirstColumn >> 16;
                    int var31 = destColumnCount;
                    int var32;
                    if (this.clipLeft > var30) {
                        var32 = this.clipLeft - var30;
                        var31 = destColumnCount - var32;
                        srcStartX += var32 * scaleX;
                        var30 = this.clipLeft;
                    }

                    if (this.clipRight <= var30 + var31) {
                        var32 = var30 - this.clipRight + var31;
                        var31 -= var32;
                    }

                    skipEveryOther = 1 - skipEveryOther;
                    if (skipEveryOther != 0) {
                        for (var32 = var30; var30 + var31 > var32; ++var32) {
                            spritePixel = src[var29 + (srcStartX >> 16)];
                            if (spritePixel != 0) {
                                int spritePixelR = spritePixel >> 16 & 0xFF;
                                int spritePixelG = spritePixel >> 8 & 0xFF;
                                int spritePixelB = spritePixel & 0xFF;

                                // Is the colour from the sprite gray?
                                if (spritePixelR == spritePixelG && spritePixelG == spritePixelB) {
                                    spritePixelR = (spritePixelR * mask1R) >> 8;
                                    spritePixelG = (spritePixelG * mask1G) >> 8;
                                    spritePixelB = (spritePixelB * mask1B) >> 8;
                                } else if (spritePixelR == 255
                                        && spritePixelG == spritePixelB) { // Is sprite colour full white?
                                    spritePixelR = (spritePixelR * mask2R) >> 8;
                                    spritePixelG = (spritePixelG * mask2G) >> 8;
                                    spritePixelB = (spritePixelB * mask2B) >> 8;
                                } else if (blueMask != 0xFFFFFF
                                        && spritePixelR == spritePixelG
                                        && spritePixelB != spritePixelG) {
                                    int blueMaskR = blueMask >> 16 & 0xFF;
                                    int blueMaskG = blueMask >> 8 & 0xFF;
                                    int blueMaskB = blueMask & 0xFF;
                                    int shifter = spritePixelR * spritePixelB;
                                    spritePixelR = (blueMaskR * shifter) >> 16;
                                    spritePixelG = (blueMaskG * shifter) >> 16;
                                    spritePixelB = (blueMaskB * shifter) >> 16;
                                }

                                int opacity = colourTransform >> 24 & 0xFF;
                                int inverseOpacity = 0xFF - opacity;

                                int transformR = (colourTransform >> 16) & 0xFF;
                                int transformG = (colourTransform >> 8) & 0xFF;
                                int transformB = colourTransform & 0xFF;

                                int spriteR = ((spritePixelR * transformR) >> 8) * opacity;
                                int spriteG = ((spritePixelG * transformG) >> 8) * opacity;
                                int spriteB = ((spritePixelB * transformB) >> 8) * opacity;

                                int canvasR = (dest[var32 + destRowHead] >> 16 & 0xff) * inverseOpacity;
                                int canvasG = (dest[var32 + destRowHead] >> 8 & 0xff) * inverseOpacity;
                                int canvasB = (dest[var32 + destRowHead] & 0xff) * inverseOpacity;

                                int finalColour = (((spriteR + canvasR) >> 8) << 16)
                                        + (((spriteG + canvasG) >> 8) << 8)
                                        + ((spriteB + canvasB) >> 8);
                                dest[var32 + destRowHead] = finalColour;

                                /*if (spritePixelR == spritePixelG && spritePixelB == spritePixelG) {
                                	dest[var32 + destRowHead] = (spritePixelB * mask1B >> 8) + (mask1G * spritePixelG >> 8 << 8)
                                			+ (spritePixelR * mask1R >> 8 << 16);
                                } else if (spritePixelR == 255 && spritePixelG == spritePixelB) {
                                	dest[var32 + destRowHead] = (mask2B * spritePixelB >> 8) + (spritePixelR * mask2R >> 8 << 16)
                                			+ (spritePixelG * mask2G >> 8 << 8);
                                } else {
                                	dest[var32 + destRowHead] = spritePixel;
                                }*/
                            }

                            srcStartX += scaleX;
                        }
                    }

                    srcStartY += scaleY;
                    srcStartX = var27;
                    destRowHead += this.width2;
                    destFirstColumn += destColumnSkewPerRow;
                }
            } catch (Exception var33) {
                System.out.println("error in transparent sprite plot routine");
            }

        } catch (RuntimeException var34) {
            throw GenUtil.makeThrowable(
                    var34,
                    "ua.AA(" + (dest != null ? "{...}" : "null") + ',' + (src != null ? "{...}" : "null") + ','
                            + destColumnCount + ',' + destColumnSkewPerRow + ',' + destFirstColumn + ',' + dummy1 + ','
                            + spritePixel + ',' + mask2 + ',' + scaleY + ',' + scaleX + ',' + srcStartX + ','
                            + skipEveryOther + ',' + srcStartY + ',' + srcWidth + ',' + mask1 + ',' + destHeight + ','
                            + destRowHead + ')');
        }
    }

    public final void copyPixelDataToSurface(SPRITE_LAYER layer, int xOffset, int yOffset, int width, int height) {
        try {
            int[] pixels = new int[width * height];
            int pixel = 0;
            for (int x = xOffset; x < xOffset + width; x++) {
                for (int y = yOffset; y < yOffset + height; y++) {
                    pixels[pixel++] = pixelData[x + y * width2];
                }
            }

            Sprite sprite = new Sprite(pixels, width, height);
            sprite.setShift(0, 0);
            sprite.setRequiresShift(false);
            sprite.setSomething(width, height);

            switch (layer) {
                case MINIMAP -> {}
                case WORLDMAP -> {
                    // doesn't look like the worldmap is generated on hte fly
                    // sprites[4500] = sprite;
                }
                case SHOP -> {
                    // sprites[49] = sprite;
                }
            }

            /*
             *  this.image2D_width[destLayer] = width;
             * this.image2D_height[destLayer] = height;
             * this.image2D_hasAlpha[destLayer] = false;
             * this.image2D_xOffset[destLayer] = 0;
             * this.image2D_yOffset[destLayer] = 0;
             * this.image2D_setParam2[destLayer] = width;
             * this.image2D_setParam3[destLayer] = height; int count = height *
             * width; int destHead = 0; this.surfaceSetPixels[destLayer] = new
             * int[count];
             *
             * for (int x = xOffset; xOffset + width > x; ++x) { for (int y =
             * yOffset; y < height + yOffset; ++y) {
             * this.surfaceSetPixels[destLayer][destHead++] = this.pixelData[x +
             * this.width2 * y]; } }
             */
        } catch (RuntimeException var11) {
            throw GenUtil.makeThrowable(
                    var11,
                    "ua.BB(" + height + ',' + xOffset + ',' + yOffset + ',' + "dummy" + ',' + layer + ',' + width
                            + ')');
        }
    }

    public final void blackScreen(boolean var1) {
        try {

            int var2 = this.height2 * this.width2;
            if (var1 == !this.interlace) {
                for (int i = 0; var2 > i; ++i) {
                    this.pixelData[i] = 0;
                }
            } else {
                int head = 0;

                for (int i = -this.height2; i < 0; i += 2) {
                    for (int j = -this.width2; j < 0; ++j) {
                        this.pixelData[head++] = 0;
                    }

                    head += this.width2;
                }
            }

        } catch (RuntimeException var6) {
            throw GenUtil.makeThrowable(var6, "ua.H(" + var1 + ')');
        }
    }

    public final void drawLineHoriz(int x, int y, int width, int color) {
        try {

            if (this.clipTop <= y && y < this.clipBottom) {
                if (this.clipLeft > x) {
                    width -= this.clipLeft - x;
                    x = this.clipLeft;
                }

                if (x + width > this.clipRight) {
                    width = this.clipRight - x;
                }

                if (width > 0) {
                    int offset = x + this.width2 * y;

                    for (int xi = 0; width > xi; ++xi) {
                        this.pixelData[offset + xi] = color;
                    }
                }
            }
        } catch (RuntimeException var9) {
            throw GenUtil.makeThrowable(var9, "ua.LB(" + width + ',' + color + ',' + x + ',' + y + ',' + "dummy" + ')');
        }
    }

    public final void drawLineVert(int x, int y, int color, int height) {

        if (this.clipLeft <= x && x < this.clipRight) {
            if (y < this.clipTop) {
                height -= this.clipTop - y;
                y = this.clipTop;
            }

            if (y + height > this.clipBottom) {
                height = this.clipBottom - y;
            }

            if (height > 0) {
                int pxOffset = x + this.width2 * y;

                for (int i = 0; height > i; ++i) {
                    this.pixelData[pxOffset + this.width2 * i] = color;
                }
            }
        }
    }

    /*
     * public final void drawWorld(boolean var1, int var2) { try {
     *  int var3 = this.image2D_height[var2] *
     * this.image2D_width[var2]; int[] var4 = this.image2D_pixels[var2]; int[]
     * var5 = new int['\u8000'];
     *
     * for (int var6 = 0; var6 < var3; ++var6) { int var7 = var4[var6];
     * ++var5[(31 & var7 >> 3) + (var7 >> 9 & 31744) + ((var7 & '\uf800') >>
     * 6)]; }
     *
     * int[] var24 = new int[256]; var24[0] = 16711935; int[] var25 = new
     * int[256];
     *
     * int var9; int var10; int var11; for (int var8 = 0; var8 < '\u8000';
     * ++var8) { var9 = var5[var8]; if (var25[255] < var9) { for (var10 = 1;
     * var10 < 256; ++var10) { if (var25[var10] < var9) { for (var11 = 255;
     * var10 < var11; --var11) { var24[var11] = var24[var11 - 1]; var25[var11] =
     * var25[var11 - 1]; }
     *
     * var24[var10] = 0x040404 + (FastMath.bitwiseAnd(0x1F, var8) << 3) +
     * FastMath.bitwiseAnd(0xF800, var8 << 6) + FastMath.bitwiseAnd(0xF80000,
     * var8 << 9); var25[var10] = var9; break; } } }
     *
     * var5[var8] = -1; }
     *
     * byte[] var26 = new byte[var3];
     *
     * for (var9 = 0; var9 < var3; ++var9) { var10 = var4[var9]; var11 = (var10
     * >> 3 & 31) + ((var10 & '\uf800') >> 6) + ((16252928 & var10) >> 9); int
     * var12 = var5[var11]; if (var12 == -1) { int var13 = 999999999; int var14
     * = 255 & var10 >> 16; int var15 = 255 & var10 >> 8; int var16 = var10 &
     * 255;
     *
     * for (int var17 = 0; var17 < 256; ++var17) { int var18 = var24[var17]; int
     * var19 = (0xFF1E98 & var18) >> 16; int var20 = var18 >> 8 & 0xFF; int
     * var21 = 0xFF & var18; int var22 = (var16 - var21) * (var16 - var21) +
     * (var14 - var19) * (var14 - var19) + (var15 - var20) * (var15 - var20); if
     * (var22 < var13) { var12 = var17; var13 = var22; } }
     *
     * var5[var11] = var12; }
     *
     * var26[var9] = (byte) var12; }
     *
     * this.spriteColours[var2] = var26; this.image2D_colorLookupTable[var2] =
     * var24; this.image2D_pixels[var2] = null; } catch (RuntimeException var23)
     * { throw GenUtil.makeThrowable(var23, "ua.DB(" + false + ',' + var2 +
     * ')'); } }
     */

    public final void drawSprite(Sprite sprite, int x, int y, int destWidth, int destHeight, int var5) {
        try {
            try {
                int spriteWidth = sprite.getWidth();
                int spriteHeight = sprite.getHeight();
                int srcStartX = 0;
                int srcStartY = 0;
                int scaleX = (spriteWidth << 16) / destWidth;
                int scaleY = (spriteHeight << 16) / destHeight;
                int destHead;
                int destRowStride;
                if (sprite.requiresShift()) {
                    destHead = sprite.getSomething1();
                    destRowStride = sprite.getSomething2();
                    if (destHead == 0 || destRowStride == 0) {
                        return;
                    }

                    if (sprite.getYShift() * destHeight % destRowStride != 0) {
                        srcStartY =
                                ((destRowStride - destHeight * sprite.getYShift() % destRowStride) << 16) / destHeight;
                    }

                    scaleX = (destHead << 16) / destWidth;
                    if (sprite.getXShift() * destWidth % destHead != 0) {
                        srcStartX = ((destHead - sprite.getXShift() * destWidth % destHead) << 16) / destWidth;
                    }

                    x += (destWidth * sprite.getXShift() + destHead - 1) / destHead;
                    scaleY = (destRowStride << 16) / destHeight;
                    y += (destRowStride + destHeight * sprite.getYShift() - 1) / destRowStride;
                    destHeight = (sprite.getHeight() - (srcStartY >> 16)) * destHeight / destRowStride;
                    destWidth = destWidth * (sprite.getWidth() - (srcStartX >> 16)) / destHead;
                }

                destHead = x + this.width2 * y;
                if (y < this.clipTop) {
                    int lost = this.clipTop - y;
                    srcStartY += scaleY * lost;
                    destHeight -= lost;
                    destHead += this.width2 * lost;
                    y = 0;
                }

                destRowStride = this.width2 - destWidth;
                if (y + destHeight >= this.clipBottom) {
                    destHeight -= y - this.clipBottom + destHeight + 1;
                }

                if (x < this.clipLeft) {
                    int lost = this.clipLeft - x;
                    destWidth -= lost;
                    destRowStride += lost;
                    destHead += lost;
                    x = 0;
                    srcStartX += scaleX * lost;
                }

                if (this.clipRight <= x + destWidth) {
                    int lost = 1 + x + (destWidth - this.clipRight);
                    destRowStride += lost;
                    destWidth -= lost;
                }

                byte heightStep = 1;
                if (this.interlace) {
                    if ((y & 1) != 0) {
                        --destHeight;
                        destHead += this.width2;
                    }

                    destRowStride += this.width2;
                    heightStep = 2;
                    scaleY += scaleY;
                }

                this.plot_scale_black_mask(
                        sprite.getPixels(),
                        heightStep,
                        scaleX,
                        0,
                        srcStartY,
                        this.pixelData,
                        (byte) 78,
                        scaleY,
                        destHeight,
                        srcStartX,
                        destRowStride,
                        destWidth,
                        spriteWidth,
                        destHead);
            } catch (Exception var16) {
                System.out.println("drawSprite: error in sprite clipping routine");
            }

        } catch (RuntimeException var17) {
            throw GenUtil.makeThrowable(
                    var17,
                    "ua.D(" + x + ',' + y + ',' + destHeight + ',' + destWidth + ',' + 5924 + ',' + sprite + ')');
        }
    }

    public final void setPixel(int x, int y, int val) {
        try {

            if (this.clipLeft <= x && this.clipTop <= y && this.clipRight > x && this.clipBottom > y) {
                this.pixelData[x + this.width2 * y] = val;
            }
        } catch (RuntimeException var6) {
            throw GenUtil.makeThrowable(var6, "ua.CB(" + y + ',' + x + ',' + "dummy" + ',' + val + ')');
        }
    }

    public final void drawSpriteClipping(
            Sprite sprite,
            int x,
            int y,
            int width,
            int height,
            int colorMask,
            int colorMask2,
            int blueMask,
            boolean mirrorX,
            int topPixelSkew,
            int dummy) {
        drawSpriteClipping(
                sprite, x, y, width, height, colorMask, colorMask2, blueMask, mirrorX, topPixelSkew, dummy, 0xFFFFFFFF);
    }

    public final void drawSpriteClipping(
            Sprite e,
            int x,
            int y,
            int width,
            int height,
            int colorMask,
            int colorMask2,
            int blueMask,
            boolean mirrorX,
            int topPixelSkew,
            int dummy,
            int colourTransform) {
        try {
            try {

                if (colorMask2 == 0) {
                    colorMask2 = 0xFFFFFF;
                }

                if (colorMask == 0) {
                    colorMask = 0xFFFFFF;
                }

                if (e == null) {
                    e = Sprite.getUnknownSprite(width, height);
                }

                int spriteWidth = e.getWidth();
                int spriteHeight = e.getHeight();
                int srcStartX = 0;
                int srcStartY = 0;
                int destFirstColumn = topPixelSkew << 16;
                int scaleX = (spriteWidth << 16) / width;
                int scaleY = (spriteHeight << 16) / height;
                int destColumnSkewPerRow = -(topPixelSkew << 16) / height;
                int destRowHead;
                int skipEveryOther;
                if (e.requiresShift()) {
                    destRowHead = e.getSomething1();
                    skipEveryOther = e.getSomething2();
                    if (destRowHead == 0 || skipEveryOther == 0) {
                        return;
                    }

                    scaleX = (destRowHead << 16) / width;
                    scaleY = (skipEveryOther << 16) / height;
                    int var21 = e.getXShift();
                    if (mirrorX) {
                        var21 = destRowHead - e.getWidth() - var21;
                    }

                    int var22 = e.getYShift();
                    x += (destRowHead + var21 * width - 1) / destRowHead;
                    int var23 = (var22 * height + skipEveryOther - 1) / skipEveryOther;
                    if (var21 * width % destRowHead != 0) {
                        srcStartX = ((destRowHead - width * var21 % destRowHead) << 16) / width;
                    }

                    y += var23;
                    destFirstColumn += var23 * destColumnSkewPerRow;
                    if (var22 * height % skipEveryOther != 0) {
                        srcStartY = ((skipEveryOther - height * var22 % skipEveryOther) << 16) / height;
                    }

                    width = (scaleX + ((e.getWidth() << 16) - (srcStartX + 1))) / scaleX;
                    height = ((e.getHeight() << 16) - srcStartY - (1 - scaleY)) / scaleY;
                }

                destRowHead = this.width2 * y;
                destFirstColumn += x << 16;
                if (y < this.clipTop) {
                    skipEveryOther = this.clipTop - y;
                    destFirstColumn += destColumnSkewPerRow * skipEveryOther;
                    height -= skipEveryOther;
                    srcStartY += skipEveryOther * scaleY;
                    destRowHead += this.width2 * skipEveryOther;
                    y = this.clipTop;
                }

                if (y + height >= this.clipBottom) {
                    height -= 1 + y + height - this.clipBottom;
                }

                skipEveryOther = destRowHead / this.width2 & dummy;
                if (!this.interlace) {
                    skipEveryOther = 2;
                }
                // TODO:Make sure this works.
                if (colorMask2 == 0xFFFFFF) {
                    if (null != e.getPixels()) {
                        if (mirrorX) {
                            this.plot_tran_scale_with_mask(
                                    dummy ^ 74,
                                    e.getPixels(),
                                    scaleY,
                                    0,
                                    srcStartY,
                                    (e.getWidth() << 16) - (srcStartX + 1),
                                    width,
                                    this.pixelData,
                                    height,
                                    destColumnSkewPerRow,
                                    destRowHead,
                                    -scaleX,
                                    destFirstColumn,
                                    spriteWidth,
                                    skipEveryOther,
                                    colorMask,
                                    colourTransform,
                                    blueMask);
                        } else {
                            this.plot_tran_scale_with_mask(
                                    dummy + 89,
                                    e.getPixels(),
                                    scaleY,
                                    0,
                                    srcStartY,
                                    srcStartX,
                                    width,
                                    this.pixelData,
                                    height,
                                    destColumnSkewPerRow,
                                    destRowHead,
                                    scaleX,
                                    destFirstColumn,
                                    spriteWidth,
                                    skipEveryOther,
                                    colorMask,
                                    colourTransform,
                                    blueMask);
                        }
                    }
                } else if (mirrorX) {
                    this.plot_trans_scale_with_2_masks(
                            this.pixelData,
                            e.getPixels(),
                            width,
                            destColumnSkewPerRow,
                            destFirstColumn,
                            dummy + 1603920391,
                            0,
                            colorMask2,
                            scaleY,
                            -scaleX,
                            (e.getWidth() << 16) - srcStartX - 1,
                            skipEveryOther,
                            srcStartY,
                            spriteWidth,
                            colorMask,
                            height,
                            destRowHead,
                            colourTransform,
                            blueMask);
                } else {
                    this.plot_trans_scale_with_2_masks(
                            this.pixelData,
                            e.getPixels(),
                            width,
                            destColumnSkewPerRow,
                            destFirstColumn,
                            1603920392,
                            0,
                            colorMask2,
                            scaleY,
                            scaleX,
                            srcStartX,
                            skipEveryOther,
                            srcStartY,
                            spriteWidth,
                            colorMask,
                            height,
                            destRowHead,
                            colourTransform,
                            blueMask);
                }
            } catch (Exception var24) {
                System.out.println("drawSpriteClipping: error in sprite clipping routine");
            }

        } catch (RuntimeException var25) {
            throw GenUtil.makeThrowable(
                    var25,
                    "ua.AB(" + y + ',' + colorMask + ',' + colorMask2 + ',' + mirrorX + ',' + topPixelSkew + ',' + e
                            + ',' + height + ',' + width + ',' + x + ',' + dummy + ')');
        }
    }

    private void plot_tran_scale_with_mask(
            int dummy2,
            int[] src,
            int scaleY,
            int dummy1,
            int srcStartY,
            int srcStartX,
            int destColumnCount,
            int[] dest,
            int destHeight,
            int destColumnSkewPerRow,
            int destRowHead,
            int scaleX,
            int destFirstColumn,
            int srcWidth,
            int skipEveryOther,
            int spritePixel,
            int colourTransform,
            int blueMask) {
        try {

            int spritePixelR = spritePixel >> 16 & 0xFF;
            int spritePixelG = spritePixel >> 8 & 0xFF;
            int spritePixelB = spritePixel & 0xFF;

            if (blueMask == 0) blueMask = 0xFFFFFF;
            try {
                int firstColumn = srcStartX;

                for (int i = -destHeight; i < 0; ++i) {
                    int srcRowHead = (srcStartY >> 16) * srcWidth;
                    int duFirstColumn = destFirstColumn >> 16;
                    int duColumnCount = destColumnCount;
                    if (duFirstColumn < this.clipLeft) {
                        int lost = this.clipLeft - duFirstColumn;
                        duFirstColumn = this.clipLeft;
                        duColumnCount = destColumnCount - lost;
                        srcStartX += scaleX * lost;
                    }

                    skipEveryOther = 1 - skipEveryOther;
                    if (duFirstColumn + duColumnCount >= this.clipRight) {
                        int lost = duColumnCount + duFirstColumn - this.clipRight;
                        duColumnCount -= lost;
                    }

                    if (skipEveryOther != 0) {
                        for (int j = duFirstColumn; j < duColumnCount + duFirstColumn; ++j) {
                            int newColor = src[srcRowHead + (srcStartX >> 16)];
                            if (newColor != 0) {
                                int opacity = colourTransform >> 24 & 0xFF;
                                int inverseOpacity = 256 - opacity;

                                int transformR = colourTransform >> 16 & 0xFF;
                                int transformG = colourTransform >> 8 & 0xFF;
                                int transformB = colourTransform & 0xFF;

                                int newR = newColor >> 16 & 0xFF;
                                int newG = newColor >> 8 & 0xFF;
                                int newB = newColor & 0xFF;

                                // Is the colour from the sprite gray?
                                if (newR == newG && newG == newB) {
                                    newR = (spritePixelR * newR) >> 8;
                                    newG = (spritePixelG * newG) >> 8;
                                    newB = (spritePixelB * newB) >> 8;
                                } else if (blueMask != 0xFFFFFF && newR == newG && newB != newR) { // blue mask?
                                    int blueMaskR = blueMask >> 16 & 0xFF;
                                    int blueMaskG = blueMask >> 8 & 0xFF;
                                    int blueMaskB = blueMask & 0xFF;
                                    int shifter = newR * newB;
                                    newR = (blueMaskR * shifter) >> 16;
                                    newG = (blueMaskG * shifter) >> 16;
                                    newB = (blueMaskB * shifter) >> 16;
                                }

                                int spriteR = ((newR * transformR) >> 8) * opacity;
                                int spriteG = ((newG * transformG) >> 8) * opacity;
                                int spriteB = ((newB * transformB) >> 8) * opacity;

                                int canvasR = (dest[destRowHead + j] >> 16 & 0xff) * inverseOpacity;
                                int canvasG = (dest[destRowHead + j] >> 8 & 0xff) * inverseOpacity;
                                int canvasB = (dest[destRowHead + j] & 0xff) * inverseOpacity;

                                int finalColour = (((spriteR + canvasR) >> 8) << 16)
                                        + (((spriteG + canvasG) >> 8) << 8)
                                        + ((spriteB + canvasB) >> 8);
                                dest[destRowHead + j] = finalColour;

                                /*// Are we a grey?
                                if (newR == newG && newB == newG) {
                                	dest[destRowHead + j] = (newB * backgroundB >> 8) + ((backgroundG * newG >> 8) << 8)
                                			+ ((backgroundR * newR >> 8) << 16);
                                } else {
                                	dest[destRowHead + j] = newColor;
                                }*/
                            }

                            srcStartX += scaleX;
                        }
                    }

                    srcStartY += scaleY;
                    srcStartX = firstColumn;
                    destFirstColumn += destColumnSkewPerRow;
                    destRowHead += this.width2;
                }
            } catch (Exception var29) {
                System.out.println("error in transparent sprite plot routine");
            }

            if (dummy2 < 20) {}

        } catch (RuntimeException var30) {
            throw GenUtil.makeThrowable(
                    var30,
                    "ua.GA(" + dummy2 + ',' + (src != null ? "{...}" : "null") + ',' + scaleY + ',' + dummy1 + ','
                            + srcStartY + ',' + srcStartX + ',' + destColumnCount + ','
                            + (dest != null ? "{...}" : "null") + ',' + destHeight + ',' + destColumnSkewPerRow + ','
                            + destRowHead + ',' + scaleX + ',' + destFirstColumn + ',' + srcWidth + ',' + skipEveryOther
                            + ',' + spritePixel + ')');
        }
    }

    public boolean fillSpriteTree() {
        File workspaceFile = new File(Config.F_CACHE_DIR, "video" + File.separator + "Custom_Sprites.osar");
        if (!workspaceFile.exists()) return false;

        Unpacker unpacker = new Unpacker();
        Workspace workspace = unpacker.unpackArchive(workspaceFile);
        for (Subspace subspace : workspace.getSubspaces()) {
            Map<String, Entry> entries = new HashMap<>();
            for (Entry entry : subspace.getEntryList()) entries.put(entry.getID(), entry);
            spriteTree.put(subspace.getName(), entries);
        }

        return true;
    }

    public boolean loadSprite(int id, String packageName) {
        try {
            ZipEntry e = spriteArchive.getEntry(String.valueOf(id));
            if (e == null) {
                if (Config.DEBUG) System.err.println("Missing sprite: " + id + " from package " + packageName);
                sprites[id] = Sprite.getUnknownSprite(48, 32);
                return true;
            }
            ByteBuffer data = DataConversions.streamToBuffer(new BufferedInputStream(spriteArchive.getInputStream(e)));
            sprites[id] = Sprite.unpack(data);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
