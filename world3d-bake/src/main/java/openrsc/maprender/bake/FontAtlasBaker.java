package openrsc.maprender.bake;

import com.openrsc.data.DataFileDecrypter;
import com.openrsc.data.DataOperations;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * Bakes the client's bold 12px font, {@code h12b.jf}, as a white-on-transparent glyph atlas
 * ({@code font-h12b.png} and {@code .json}) and as a TrueType face ({@code font-h12b.ttf}).
 *
 * <p>The font holds a 9-byte record per glyph: a 21-bit bitmap address in 7-bit bytes, then width,
 * height, x offset, y offset (the glyph's top is that far above the baseline) and advance, all in
 * pixels. The bitmap is one byte per pixel, nonzero where lit. The line height is byte 8, less one.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L150">GraphicsController.plotCharacter</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L1275">GraphicsController.c</a>
 */
public final class FontAtlasBaker {

    private record Glyph(int c, int x, int y, int w, int h, int xo, int yo, int adv) {}

    private record Atlas(long baked, int width, int height, int lineHeight, List<Glyph> chars) {}

    /**
     * The client's character set: glyph record {@code i} draws {@code CHARSET.charAt(i)}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/Fonts.java#L13">Fonts.inputFilterChars</a>
     */
    public static final String CHARSET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!\"\u00a3$%^&*()-_=+[{]};:'@#~,<.>/?\\| ";

    /** The family name the TrueType face declares. */
    public static final String FAMILY = "RSC h12b";

    private static final int PADDING = 1;

    public static void export(String cacheDir, File outDir, Consumer<String> log) throws Exception {
        byte[] archive = unpackArchive(Path.of(cacheDir, "video", "library.orsc"));
        byte[] font = DataOperations.loadData("h12b.jf", 0, archive);
        if (font == null) {
            throw new IllegalStateException("h12b.jf missing from library.orsc");
        }

        int lineHeight = font[8] - 1;

        int atlasW = PADDING;
        int atlasH = 0;
        for (int i = 0; i < CHARSET.length(); i++) {
            int idx = i * 9;
            atlasW += Math.max(0, font[idx + 3]) + PADDING;
            atlasH = Math.max(atlasH, Math.max(0, font[idx + 4]));
        }
        atlasH += 2 * PADDING;

        BufferedImage atlas = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        List<Glyph> chars = new ArrayList<>(CHARSET.length());
        int penX = PADDING;
        for (int i = 0; i < CHARSET.length(); i++) {
            int idx = i * 9;
            int w = Math.max(0, font[idx + 3]);
            int h = Math.max(0, font[idx + 4]);
            int xo = font[idx + 5];
            int yo = font[idx + 6];
            int adv = font[idx + 7];
            int dataAddr = (font[idx] << 14) + (font[idx + 1] << 7) + font[idx + 2];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (font[dataAddr + y * w + x] != 0) {
                        atlas.setRGB(penX + x, PADDING + y, 0xFFFFFFFF);
                    }
                }
            }
            chars.add(new Glyph(CHARSET.charAt(i), penX, PADDING, w, h, xo, yo, adv));
            penX += w + PADDING;
        }

        List<TrueTypeWriter.GlyphSpec> ttfGlyphs = new ArrayList<>(CHARSET.length());
        int ascent = 1;
        int descent = 1;
        for (Glyph g : chars) {
            ascent = Math.max(ascent, g.yo());
            descent = Math.max(descent, g.h() - g.yo());
        }
        for (int i = 0; i < CHARSET.length(); i++) {
            int idx = i * 9;
            int w = Math.max(0, font[idx + 3]);
            int h = Math.max(0, font[idx + 4]);
            int dataAddr = (font[idx] << 14) + (font[idx + 1] << 7) + font[idx + 2];
            ttfGlyphs.add(new TrueTypeWriter.GlyphSpec(
                    CHARSET.charAt(i),
                    font[idx + 7],
                    font[idx + 5],
                    font[idx + 6],
                    TrueTypeWriter.mergePixels((x, y) -> font[dataAddr + y * w + x] != 0, w, h)));
        }
        byte[] ttf = TrueTypeWriter.build(FAMILY, ascent, descent, ttfGlyphs);
        Files.write(new File(outDir, "font-h12b.ttf").toPath(), ttf);

        ImageIO.write(atlas, "png", new File(outDir, "font-h12b.png"));
        BakeJson.MAPPER.writeValue(
                new File(outDir, "font-h12b.json"),
                new Atlas(System.currentTimeMillis(), atlasW, atlasH, lineHeight, chars));
        log.accept("font atlas h12b: " + CHARSET.length() + " glyphs, " + atlasW + "x" + atlasH + ", lineHeight "
                + lineHeight + ", ttf " + ttf.length + " bytes");
    }

    private static byte[] unpackArchive(Path path) throws Exception {
        byte[] raw = Files.readAllBytes(path);
        int decmpLen = ((raw[0] & 0xFF) << 16) | ((raw[1] & 0xFF) << 8) | (raw[2] & 0xFF);
        int cmpLen = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
        byte[] payload = Arrays.copyOfRange(raw, 6, 6 + cmpLen);
        if (cmpLen != decmpLen) {
            byte[] out = new byte[decmpLen];
            DataFileDecrypter.unpackData(out, decmpLen, payload, cmpLen, 0);
            return out;
        }
        return payload;
    }

    private FontAtlasBaker() {}
}
