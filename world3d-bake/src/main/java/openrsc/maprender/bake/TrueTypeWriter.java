package openrsc.maprender.bake;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a TrueType font from bitmap glyphs. Each glyph's lit pixels become square contours on
 * pixel boundaries, so the face renders exactly at its design size: with an em of
 * {@link #EM_PIXELS} pixels, text set at 16px draws one glyph pixel per CSS pixel.
 */
public final class TrueTypeWriter {

    /** Lit pixels in a glyph bitmap, in pixels: x to the right, y down from the glyph's top. */
    public record PixelRect(int x, int y, int w, int h) {}

    /**
     * One glyph, in pixels: {@code adv} is its advance, {@code xo} its left bearing and {@code yo}
     * how far its top stands above the baseline. {@code code} is a Unicode code point.
     */
    public record GlyphSpec(int code, int adv, int xo, int yo, List<PixelRect> rects) {}

    public interface Lit {
        boolean at(int x, int y);
    }

    /** Font units per glyph pixel. */
    public static final int UNITS_PER_PIXEL = 64;

    public static final int EM_PIXELS = 16;
    public static final int UNITS_PER_EM = UNITS_PER_PIXEL * EM_PIXELS;

    /**
     * Covers a {@code w} by {@code h} bitmap's lit pixels with rectangles: each row's runs, each run
     * extended down over identical runs below it.
     */
    public static List<PixelRect> mergePixels(Lit lit, int w, int h) {
        List<List<int[]>> runs = new ArrayList<>(h);
        for (int y = 0; y < h; y++) {
            List<int[]> row = new ArrayList<>();
            int x = 0;
            while (x < w) {
                if (!lit.at(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < w && lit.at(x, y)) {
                    x++;
                }
                row.add(new int[] {start, x - start});
            }
            runs.add(row);
        }
        List<PixelRect> out = new ArrayList<>();
        boolean[][] taken = new boolean[h][];
        for (int y = 0; y < h; y++) {
            taken[y] = new boolean[runs.get(y).size()];
        }
        for (int y = 0; y < h; y++) {
            for (int i = 0; i < runs.get(y).size(); i++) {
                if (taken[y][i]) {
                    continue;
                }
                int[] run = runs.get(y).get(i);
                int height = 1;
                for (int y2 = y + 1; y2 < h; y2++) {
                    int j = -1;
                    for (int k = 0; k < runs.get(y2).size(); k++) {
                        int[] r = runs.get(y2).get(k);
                        if (!taken[y2][k] && r[0] == run[0] && r[1] == run[1]) {
                            j = k;
                            break;
                        }
                    }
                    if (j < 0) {
                        break;
                    }
                    taken[y2][j] = true;
                    height++;
                }
                out.add(new PixelRect(run[0], y, run[1], height));
            }
        }
        return out;
    }

    /**
     * Returns the font file. Glyph 0 is an empty {@code .notdef}; the rest follow in code-point
     * order. {@code ascent} and {@code descent} are the ink's extent above and below the baseline,
     * in pixels; {@code input} must not be empty.
     */
    public static byte[] build(String family, int ascent, int descent, List<GlyphSpec> input) {
        int p = UNITS_PER_PIXEL;
        List<GlyphSpec> glyphs = new ArrayList<>(input);
        glyphs.sort(Comparator.comparingInt(GlyphSpec::code));
        int numGlyphs = glyphs.size() + 1;

        W glyfW = new W();
        int[] locaOffsets = new int[numGlyphs + 1];
        locaOffsets[0] = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            locaOffsets[i + 1] = glyfW.length();
            glyfW.bytes(glyfEntry(glyphs.get(i)));
        }
        locaOffsets[numGlyphs] = glyfW.length();
        byte[] glyf = glyfW.done();

        W locaW = new W();
        for (int o : locaOffsets) {
            locaW.u32(o);
        }
        byte[] loca = locaW.done();

        int[] advances = new int[numGlyphs];
        int[] lsbs = new int[numGlyphs];
        for (int i = 0; i < glyphs.size(); i++) {
            advances[i + 1] = glyphs.get(i).adv() * p;
            lsbs[i + 1] = glyphs.get(i).xo() * p;
        }
        W hmtxW = new W();
        for (int i = 0; i < numGlyphs; i++) {
            hmtxW.u16(advances[i]).i16(lsbs[i]);
        }
        byte[] hmtx = hmtxW.done();

        int asc = ascent * p;
        int desc = -descent * p;
        int xMax = p;
        for (GlyphSpec g : glyphs) {
            for (PixelRect r : g.rects()) {
                xMax = Math.max(xMax, (g.xo() + r.x() + r.w()) * p);
            }
        }

        W head = new W();
        head.u32(0x00010000L);
        head.u32(0x00010000L);
        head.u32(0); // checkSumAdjustment, set once the whole file is written
        head.u32(0x5F0F3CF5L);
        head.u16(0x000B);
        head.u16(UNITS_PER_EM);
        head.u32(0).u32(0);
        head.u32(0).u32(0);
        head.i16(0).i16(desc).i16(xMax).i16(asc);
        head.u16(0);
        head.u16(8);
        head.i16(2);
        head.i16(1); // indexToLocFormat: the long loca written above
        head.i16(0);
        byte[] headT = head.done();

        W hhea = new W();
        hhea.u32(0x00010000L);
        hhea.i16(asc).i16(desc).i16(0);
        hhea.u16(max(advances));
        hhea.i16(0).i16(0).i16(xMax);
        hhea.i16(1).i16(0).i16(0);
        hhea.i16(0).i16(0).i16(0).i16(0);
        hhea.i16(0);
        hhea.u16(numGlyphs);
        byte[] hheaT = hhea.done();

        int maxPoints = 4;
        int maxContours = 1;
        for (GlyphSpec g : glyphs) {
            maxPoints = Math.max(maxPoints, g.rects().size() * 4);
            maxContours = Math.max(maxContours, g.rects().size());
        }
        W maxp = new W();
        maxp.u32(0x00010000L);
        maxp.u16(numGlyphs).u16(maxPoints).u16(maxContours);
        maxp.u16(0).u16(0);
        maxp.u16(2).u16(0);
        maxp.u16(0).u16(0).u16(0);
        maxp.u16(0).u16(0);
        maxp.u16(0).u16(0);
        byte[] maxpT = maxp.done();

        // Glyph ids follow code-point order, so each run of consecutive code points is one segment.
        List<int[]> segs = new ArrayList<>();
        for (int i = 0; i < glyphs.size(); i++) {
            int code = glyphs.get(i).code();
            int id = i + 1;
            int[] last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
            if (last != null && code == last[1] + 1 && ((id - code) & 0xFFFF) == last[2]) {
                last[1] = code;
            } else {
                segs.add(new int[] {code, code, (id - code) & 0xFFFF});
            }
        }
        // The format requires a final 0xFFFF segment.
        segs.add(new int[] {0xFFFF, 0xFFFF, 1});
        int segCount = segs.size();
        W sub = new W();
        sub.u16(4).u16(16 + segCount * 8).u16(0);
        sub.u16(segCount * 2);
        int entrySel = (int) Math.floor(Math.log(segCount) / Math.log(2));
        int searchRange = 2 * (int) Math.pow(2, entrySel);
        sub.u16(searchRange).u16(entrySel).u16(segCount * 2 - searchRange);
        for (int[] s : segs) {
            sub.u16(s[1]);
        }
        sub.u16(0);
        for (int[] s : segs) {
            sub.u16(s[0]);
        }
        for (int[] s : segs) {
            sub.u16(s[2]);
        }
        for (int i = 0; i < segCount; i++) {
            sub.u16(0);
        }
        W cmap = new W();
        cmap.u16(0).u16(1);
        cmap.u16(3).u16(1).u32(12);
        cmap.bytes(sub.done());
        byte[] cmapT = cmap.done();

        String[][] names = {
            {"1", family}, {"2", "Regular"}, {"3", family + "-bitmap"},
            {"4", family}, {"5", "Version 1.0"}, {"6", family.replaceAll("\\s+", "")},
        };
        W name = new W();
        name.u16(0).u16(names.length).u16(6 + names.length * 12);
        int strOff = 0;
        byte[][] blobs = new byte[names.length][];
        for (int i = 0; i < names.length; i++) {
            blobs[i] = utf16be(names[i][1]);
        }
        for (int i = 0; i < names.length; i++) {
            name.u16(3).u16(1).u16(0x0409).u16(Integer.parseInt(names[i][0]));
            name.u16(blobs[i].length).u16(strOff);
            strOff += blobs[i].length;
        }
        for (byte[] b : blobs) {
            name.bytes(b);
        }
        byte[] nameT = name.done();

        W post = new W();
        post.u32(0x00030000L);
        post.u32(0);
        post.i16(0).i16(0);
        post.u32(1);
        post.u32(0).u32(0).u32(0).u32(0);
        byte[] postT = post.done();

        long advSum = 0;
        for (int a : advances) {
            advSum += a;
        }
        W os2 = new W();
        os2.u16(4);
        os2.i16((int) (advSum / numGlyphs));
        os2.u16(700);
        os2.u16(5);
        os2.u16(0);
        os2.i16(p * 5).i16(p * 7).i16(0).i16(0);
        os2.i16(p * 5).i16(p * 7).i16(0).i16(p * 5);
        os2.i16(p).i16(p * 4);
        os2.i16(0);
        for (int i = 0; i < 10; i++) {
            os2.u8(0);
        }
        os2.u32(1).u32(0).u32(0).u32(0);
        os2.tag("RSC ");
        os2.u16(0x0040);
        os2.u16(glyphs.get(0).code());
        os2.u16(glyphs.get(glyphs.size() - 1).code());
        os2.i16(asc).i16(desc).i16(0);
        os2.u16(asc).u16(descent * p);
        os2.u32(1).u32(0);
        os2.i16(p * 5).i16(asc);
        os2.u16(0x20).u16(0x20);
        os2.u16(1);
        byte[] os2T = os2.done();

        String[] tags = {"OS/2", "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post"};
        byte[][] data = {os2T, cmapT, glyf, headT, hheaT, hmtx, loca, maxpT, nameT, postT};

        int n = tags.length;
        int entrySelector = (int) Math.floor(Math.log(n) / Math.log(2));
        int searchRangeT = (int) Math.pow(2, entrySelector) * 16;
        W out = new W();
        out.u32(0x00010000L);
        out.u16(n).u16(searchRangeT).u16(entrySelector).u16(n * 16 - searchRangeT);

        int[] offsets = new int[n];
        int offset = 12 + n * 16;
        for (int i = 0; i < n; i++) {
            offsets[i] = offset;
            offset += data[i].length + ((4 - (data[i].length % 4)) % 4);
        }
        for (int i = 0; i < n; i++) {
            out.tag(tags[i]).u32(checksum(data[i])).u32(offsets[i]).u32(data[i].length);
        }
        for (byte[] d : data) {
            out.bytes(d).align4();
        }
        byte[] file = out.done();

        int headOffset = offsets[3];
        // The spec's whole-file checksum, taken while the field is still zero.
        long adj = (0xB1B0AFBAL - checksum(file)) & 0xFFFFFFFFL;
        file[headOffset + 8] = (byte) (adj >>> 24);
        file[headOffset + 9] = (byte) (adj >>> 16);
        file[headOffset + 10] = (byte) (adj >>> 8);
        file[headOffset + 11] = (byte) adj;
        return file;
    }

    private static final class W {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);

        W u8(int v) {
            buf.write(v & 0xFF);
            return this;
        }

        W u16(int v) {
            return u8(v >> 8).u8(v);
        }

        W i16(int v) {
            return u16(v < 0 ? v + 0x10000 : v);
        }

        W u32(long v) {
            return u16((int) ((v >>> 16) & 0xFFFF)).u16((int) (v & 0xFFFF));
        }

        W tag(String s) {
            for (int i = 0; i < 4; i++) {
                u8(s.charAt(i));
            }
            return this;
        }

        W bytes(byte[] b) {
            buf.write(b, 0, b.length);
            return this;
        }

        W align4() {
            while (buf.size() % 4 != 0) {
                u8(0);
            }
            return this;
        }

        int length() {
            return buf.size();
        }

        byte[] done() {
            return buf.toByteArray();
        }
    }

    private static long checksum(byte[] d) {
        long sum = 0;
        for (int i = 0; i < d.length; i += 4) {
            long v = ((long) (d[i] & 0xFF) << 24)
                    | ((long) (i + 1 < d.length ? d[i + 1] & 0xFF : 0) << 16)
                    | ((long) (i + 2 < d.length ? d[i + 2] & 0xFF : 0) << 8)
                    | (i + 3 < d.length ? d[i + 3] & 0xFF : 0);
            sum = (sum + v) & 0xFFFFFFFFL;
        }
        return sum;
    }

    private static byte[] utf16be(String s) {
        byte[] out = new byte[s.length() * 2];
        for (int i = 0; i < s.length(); i++) {
            out[i * 2] = (byte) (s.charAt(i) >> 8);
            out[i * 2 + 1] = (byte) s.charAt(i);
        }
        return out;
    }

    private static byte[] glyfEntry(GlyphSpec g) {
        if (g.rects().isEmpty()) {
            return new byte[0];
        }
        int p = UNITS_PER_PIXEL;
        int n = g.rects().size();
        int[] x0 = new int[n];
        int[] x1 = new int[n];
        int[] y0 = new int[n];
        int[] y1 = new int[n];
        for (int i = 0; i < n; i++) {
            PixelRect r = g.rects().get(i);
            x0[i] = (g.xo() + r.x()) * p;
            x1[i] = (g.xo() + r.x() + r.w()) * p;
            y0[i] = (g.yo() - r.y() - r.h()) * p;
            y1[i] = (g.yo() - r.y()) * p;
        }
        int[] xs = new int[n * 4];
        int[] ys = new int[n * 4];
        for (int i = 0; i < n; i++) {
            xs[i * 4] = x0[i];
            ys[i * 4] = y0[i];
            xs[i * 4 + 1] = x0[i];
            ys[i * 4 + 1] = y1[i];
            xs[i * 4 + 2] = x1[i];
            ys[i * 4 + 2] = y1[i];
            xs[i * 4 + 3] = x1[i];
            ys[i * 4 + 3] = y0[i];
        }
        W w = new W();
        w.i16(n);
        w.i16(min(x0)).i16(min(y0)).i16(max(x1)).i16(max(y1));
        for (int i = 0; i < n; i++) {
            w.u16(i * 4 + 3);
        }
        w.u16(0);
        for (int i = 0; i < xs.length; i++) {
            w.u8(0x01);
        }
        int prev = 0;
        for (int x : xs) {
            w.i16(x - prev);
            prev = x;
        }
        prev = 0;
        for (int y : ys) {
            w.i16(y - prev);
            prev = y;
        }
        return w.align4().done();
    }

    private static int min(int[] a) {
        int m = a[0];
        for (int v : a) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static int max(int[] a) {
        int m = a[0];
        for (int v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    private TrueTypeWriter() {}
}
