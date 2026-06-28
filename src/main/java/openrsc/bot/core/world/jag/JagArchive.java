package openrsc.bot.core.world.jag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * Reader for the classic RuneScape Classic cache archive ({@code .jag} / {@code .mem}), the format
 * the OpenRSC server actually loads its landscape from when {@code based_map_data >= 28} (Uranium
 * uses 64). A literal port of the server's {@code com.openrsc.server.io.JContent} so the bot's
 * collision map is sourced from the same bytes the server paths against.
 *
 * <p>Layout: a 6-byte header (3-byte uncompressed length, 3-byte compressed
 * length). When the two differ the whole archive body is BZh1-headerless bzip2 — the magic bytes
 * are reconstructed in {@link #decompress} before handing the stream to commons-compress, exactly
 * as the server does. The decompressed body is an entry table: 2-byte entry count, then 10 bytes
 * per entry (4-byte name hash, 3-byte uncompressed length, 3-byte compressed length), followed by
 * the entry payloads. Individual entries may themselves be bzip2'd.
 *
 * <p>Entries are addressed by name hash only ({@code 61*h + (upper(c) - 32)}),
 * never by stored name.
 */
public final class JagArchive {

  private byte[] data;

  private JagArchive() {
  }

  /**
   * Open a {@code .jag}/{@code .mem} archive. {@code bzip2} mirrors the server's
   * {@code useBZip2 = based_map_data >= 28}. Returns {@code null} if the file is absent or
   * malformed.
   */
  public static JagArchive open(Path file, boolean bzip2) throws IOException {
    if (file == null || !Files.exists(file)) {
      return null;
    }
    JagArchive a = new JagArchive();
    a.data = Files.readAllBytes(file);
    if (a.data.length < 6) {
      return null;
    }

    int uncompressedLength =
        ((a.data[0] & 0xFF) << 16) | ((a.data[1] & 0xFF) << 8) | (a.data[2] & 0xFF);
    int compressedLength =
        ((a.data[3] & 0xFF) << 16) | ((a.data[4] & 0xFF) << 8) | (a.data[5] & 0xFF);

    if (uncompressedLength == compressedLength) {
      byte[] body = new byte[uncompressedLength];
      System.arraycopy(a.data, 6, body, 0, uncompressedLength);
      a.data = body;
    } else {
      byte[] body = a.decompress(uncompressedLength, compressedLength, bzip2);
      if (body == null) {
        return null;
      }
      a.data = body;
    }
    return a;
  }

  /**
   * Decompress the whole-archive body. Mirrors {@code JContent.decompress}: the bzip2 magic "BZh1"
   * is written over bytes 2..5 of the raw buffer and the stream is read from offset 2 for
   * {@code compressedLength + 4} bytes.
   */
  private byte[] decompress(int uncompressedLength, int compressedLength, boolean bzip2) {
    if (!bzip2) {
      // based_map_data < 28 uses GZIP-ish BZLib; Uranium never hits this.
      throw new UnsupportedOperationException(
          "non-bzip2 jag archives (based_map_data < 28) not supported");
    }
    data[2] = 0x42; // B
    data[3] = 0x5A; // Z
    data[4] = 0x68; // h
    data[5] = 0x31; // 1
    return bunzip(data, 2, compressedLength + 4, uncompressedLength);
  }

  /**
   * Unpack a named entry, or {@code null} if absent.
   */
  public JagFile unpack(String filename) {
    int entryCount = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    String upper = filename.toUpperCase();
    int hash = 0;
    for (int i = 0; i < upper.length(); i++) {
      hash = 61 * hash + (upper.charAt(i) - 32);
    }

    int offset = 2 + (10 * entryCount);
    for (int i = 0; i < entryCount; i++) {
      int eo = i * 10;
      int entryHash = ((data[2 + eo] & 0xFF) << 24) | ((data[3 + eo] & 0xFF) << 16)
                      | ((data[4 + eo] & 0xFF) << 8) | (data[5 + eo] & 0xFF);
      int uncompressedLength =
          ((data[6 + eo] & 0xFF) << 16) | ((data[7 + eo] & 0xFF) << 8) | (data[8 + eo] & 0xFF);
      int compressedLength =
          ((data[9 + eo] & 0xFF) << 16) | ((data[10 + eo] & 0xFF) << 8) | (data[11 + eo] & 0xFF);

      if (hash == entryHash) {
        byte[] out = new byte[uncompressedLength];
        if (uncompressedLength == compressedLength) {
          System.arraycopy(data, offset, out, 0, uncompressedLength);
        } else {
          // Per-entry bzip2: prepend the BZh1 magic to the entry slice.
          byte[] src = new byte[compressedLength + 4];
          src[0] = 0x42;
          src[1] = 0x5A;
          src[2] = 0x68;
          src[3] = 0x31;
          System.arraycopy(data, offset, src, 4, compressedLength);
          out = bunzip(src, 0, src.length, uncompressedLength);
          if (out == null) {
            return null;
          }
        }
        return new JagFile(out);
      }
      offset += compressedLength;
    }
    return null;
  }

  /**
   * Read exactly {@code uncompressedLength} bytes of bzip2 from {@code src[offset, offset+length)}
   * (the slice already carries a BZh1 header). Mirrors the server's {@code BZip2.decompress}.
   */
  private static byte[] bunzip(byte[] src, int offset, int length, int uncompressedLength) {
    byte[] dest = new byte[uncompressedLength];
    try (BZip2CompressorInputStream in =
        new BZip2CompressorInputStream(new ByteArrayInputStream(src, offset, length))) {
      int read = 0;
      while (read < uncompressedLength) {
        int n = in.read(dest, read, uncompressedLength - read);
        if (n < 0) {
          break;
        }
        read += n;
      }
    } catch (Exception e) {
      return null;
    }
    return dest;
  }

  /**
   * Convenience: open and return {@code null} (not throw) on any failure.
   */
  public static JagArchive openQuiet(Path file, boolean bzip2) {
    try {
      return open(file, bzip2);
    } catch (IOException e) {
      return null;
    }
  }
}
