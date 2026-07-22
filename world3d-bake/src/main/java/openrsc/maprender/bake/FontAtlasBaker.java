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
import javax.imageio.ImageIO;

/**
 * Bakes the game's bitmap font 1 ({@code h12b.jf} — the 12px bold face the client draws overhead
 * chat with) into a white-on-transparent glyph atlas + metrics JSON, so the 3D viewer can render
 * chat bubbles pixel-identical to the stock client (which tints per @col@ code and adds the black
 * drop shadow at (+1,0)/(0,+1) — see {@code GraphicsController.drawstring}).
 *
 * <p>Font format (mudclient {@code plotCharacter}): 9-byte records per char, indexed by the
 * position of the char in the client's input-filter charset. Record: bytes 0-2 = bitmap address
 * ({@code (b0<<14)+(b1<<7)+b2}), 3 = width, 4 = height, 5 = x offset, 6 = y offset (glyph top =
 * baseline − yo), 7 = advance. Bitmap = width×height bytes, nonzero = set pixel. Font line height
 * = {@code fontData[8] - 1} (GraphicsController.fontHeight for font != 0).
 */
public final class FontAtlasBaker {

  /** The client's charset — glyph record i belongs to charAt(i); unknown chars map to index 74. */
  public static final String CHARSET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!\"\u00a3$%^&*()-_=+[{]};:'@#~,<.>/?\\| ";

  private static final int PADDING = 1;

  /** One glyph's atlas rect + engine metrics (offsets/advance). */
  private record Glyph(int c, int x, int y, int w, int h, int xo, int yo, int adv) {}

  private record Atlas(long baked, int width, int height, int lineHeight, List<Glyph> chars) {}

  public static void export(String cacheDir, File outDir,
      java.util.function.Consumer<String> log) throws Exception {
    byte[] archive = unpackArchive(
        Path.of(cacheDir, "video", "library.orsc"));
    byte[] font = DataOperations.loadData("h12b.jf", 0, archive);
    if (font == null) {
      throw new IllegalStateException("h12b.jf missing from library.orsc");
    }

    int lineHeight = font[8] - 1;

    // Measure the atlas: one shelf row, glyphs side by side.
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

    ImageIO.write(atlas, "png", new File(outDir, "font-h12b.png"));
    BakeJson.MAPPER.writeValue(new File(outDir, "font-h12b.json"),
        new Atlas(System.currentTimeMillis(), atlasW, atlasH, lineHeight, chars));
    log.accept("font atlas h12b: " + CHARSET.length() + " glyphs, " + atlasW + "x" + atlasH
        + ", lineHeight " + lineHeight);
  }

  /** Outer .orsc container unpack (3-byte decompressed + compressed lengths, then payload). */
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

  private FontAtlasBaker() {
  }
}
