package openrsc.gamedata.landscape;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a landscape from an {@code .orsc} ZIP — {@code Authentic_Landscape.orsc},
 * {@code Custom_Landscape.orsc}, {@code F2PLandscape.orsc} — the repack the stock client loads from
 * its cache and the format custom servers ship.
 *
 * <p>One ZIP entry per sector, named {@code h{floor}x{sectionX}y{sectionY}}, holding
 * {@value RawSector#SIZE} flat 10-byte tile records in x-major order. The record layout is the
 * client's {@code Tile.pack()}: elevation, texture, overlay, roof, horizontal wall, vertical wall,
 * then a big-endian {@code int} of diagonal walls — the same seven fields the JAG decode yields, so
 * both land in a plain {@link RawSector} and every downstream consumer is format-blind.
 *
 * <p>Note this dataset is <em>not</em> the one a stock OpenRSC server paths against — see
 * {@link LandscapeSource}.
 */
final class OrscLandscape implements LandscapeSource {

  /** Bytes per packed tile record. */
  private static final int TILE_BYTES = 10;

  private final Path file;
  private final ZipFile zip;

  OrscLandscape(Path file) throws IOException {
    this.file = file;
    this.zip = new ZipFile(file.toFile());
  }

  private static String sectorName(int floor, int sectionX, int sectionY) {
    return "h" + floor + "x" + sectionX + "y" + sectionY;
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
        throw new IOException("sector " + entry.getName() + " is short: expected " + packed.length
            + " bytes");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading " + entry.getName() + " from " + file, e);
    }

    ByteBuffer buf = ByteBuffer.wrap(packed); // big-endian, matching Tile.pack()
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
}
