package openrsc.gamedata.jag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * A classic RSC cache archive ({@code .jag} or {@code .mem}), read as the server reads its map
 * archives. A 6-byte header gives the body's uncompressed and compressed lengths (3 bytes each); the
 * body is headerless bzip2 when they differ. The body is an entry table addressed by a hash of the
 * upper-cased name, never by a stored name.
 *
 * <p>Immutable once opened; safe to read from several threads.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/JContent.java#L13">JContent</a>
 */
public final class JagArchive {

    private byte[] data;

    private JagArchive() {}

    /**
     * Opens and decompresses {@code file}, or returns {@code null} when it is absent, shorter than its
     * header, or its body fails to decompress. {@code bzip2} is the server's choice for map data 28
     * and later; a compressed archive without it throws {@link UnsupportedOperationException}.
     *
     * @throws IOException when the file exists but cannot be read
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L457">WorldLoader.loadWorld</a>
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

        int uncompressedLength = ((a.data[0] & 0xFF) << 16) | ((a.data[1] & 0xFF) << 8) | (a.data[2] & 0xFF);
        int compressedLength = ((a.data[3] & 0xFF) << 16) | ((a.data[4] & 0xFF) << 8) | (a.data[5] & 0xFF);

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
     * The entry named {@code filename} (case-insensitive), decompressed, or {@code null} when the
     * archive has no such entry or it fails to decompress.
     */
    public JagFile unpack(String filename) {
        int entryCount = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        String upper = filename.toUpperCase(Locale.ROOT);
        int hash = 0;
        for (int i = 0; i < upper.length(); i++) {
            hash = 61 * hash + (upper.charAt(i) - 32);
        }

        int offset = 2 + (10 * entryCount);
        for (int i = 0; i < entryCount; i++) {
            int eo = i * 10;
            int entryHash = ((data[2 + eo] & 0xFF) << 24)
                    | ((data[3 + eo] & 0xFF) << 16)
                    | ((data[4 + eo] & 0xFF) << 8)
                    | (data[5 + eo] & 0xFF);
            int uncompressedLength =
                    ((data[6 + eo] & 0xFF) << 16) | ((data[7 + eo] & 0xFF) << 8) | (data[8 + eo] & 0xFF);
            int compressedLength =
                    ((data[9 + eo] & 0xFF) << 16) | ((data[10 + eo] & 0xFF) << 8) | (data[11 + eo] & 0xFF);

            if (hash == entryHash) {
                byte[] out = new byte[uncompressedLength];
                if (uncompressedLength == compressedLength) {
                    System.arraycopy(data, offset, out, 0, uncompressedLength);
                } else {
                    // From the entry's own offset; the server reads from the body's start instead, which
                    // no map archive exercises, as their entries are all stored.
                    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/JContent.java#L29
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

    private byte[] decompress(int uncompressedLength, int compressedLength, boolean bzip2) {
        if (!bzip2) {
            throw new UnsupportedOperationException("non-bzip2 jag archives (based_map_data < 28) not supported");
        }
        data[2] = 0x42;
        data[3] = 0x5A;
        data[4] = 0x68;
        data[5] = 0x31;
        return bunzip(data, 2, compressedLength + 4, uncompressedLength);
    }

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
}
