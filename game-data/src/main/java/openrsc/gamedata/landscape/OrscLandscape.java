package openrsc.gamedata.landscape;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An {@code .orsc} landscape ZIP: one entry per sector, named {@code h<floor>x<sectionX>y<sectionY>},
 * of {@value RawSector#SIZE} 10-byte tile records in {@link RawSector} order, the diagonal wall a
 * big-endian {@code int}. Holds the ZIP open until {@link #close}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/Tile.java#L42">Tile.unpack</a>
 */
final class OrscLandscape implements LandscapeSource {

    private static final int TILE_BYTES = 10;

    private final Path file;
    private final ZipFile zip;

    OrscLandscape(Path file) throws IOException {
        this.file = file;
        this.zip = new ZipFile(file.toFile());
    }

    @Override
    public boolean exists(int floor, int sectionX, int sectionY) {
        return zip.getEntry(sectorName(floor, sectionX, sectionY)) != null;
    }

    @Override
    public RawSector sector(int floor, int sectionX, int sectionY) {
        ZipEntry entry = zip.getEntry(sectorName(floor, sectionX, sectionY));
        if (entry == null) {
            return null;
        }
        byte[] packed = new byte[RawSector.SIZE * TILE_BYTES];
        try (InputStream in = zip.getInputStream(entry)) {
            if (in.readNBytes(packed, 0, packed.length) != packed.length) {
                throw new IOException("sector " + entry.getName() + " is short: expected " + packed.length + " bytes");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading " + entry.getName() + " from " + file, e);
        }

        ByteBuffer buf = ByteBuffer.wrap(packed);
        RawSector s = new RawSector();
        for (int i = 0; i < RawSector.SIZE; i++) {
            s.groundElevation[i] = buf.get();
            s.groundTexture[i] = buf.get();
            s.groundOverlay[i] = buf.get();
            s.roofTexture[i] = buf.get();
            s.horizontalWall[i] = buf.get();
            s.verticalWall[i] = buf.get();
            s.diagonalWalls[i] = buf.getInt();
        }
        return s;
    }

    @Override
    public String describe() {
        return ".orsc landscape " + file;
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed closing " + file, e);
        }
    }

    private static String sectorName(int floor, int sectionX, int sectionY) {
        return "h" + floor + "x" + sectionX + "y" + sectionY;
    }
}
