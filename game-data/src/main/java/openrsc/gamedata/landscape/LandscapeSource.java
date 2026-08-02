package openrsc.gamedata.landscape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import openrsc.gamedata.ServerConf;

/**
 * A source of decoded landscape sectors, independent of the container they came from.
 *
 * <p>RuneScape Classic landscapes reach us in two shapes, and both decode to the same per-tile
 * fields ({@link RawSector}):
 * <ul>
 *   <li><b>JAG</b> — {@code maps{rev}.jag/.mem} (+ {@code land{rev}.jag/.mem} for terrain
 *       colour/height), the classic client archives. This is what the OpenRSC server itself paths
 *       against when {@code based_map_data >= 28} (Uranium = 64), so it is the authentic source for
 *       anything that has to agree with the live server.</li>
 *   <li><b>ORSC</b> — an {@code Authentic_Landscape.orsc} / {@code Custom_Landscape.orsc} ZIP of
 *       flat 10-byte tile records, one entry per sector. This is what custom servers ship, and what
 *       the stock client loads from its cache.</li>
 * </ul>
 *
 * <p>The two datasets are <em>not</em> interchangeable — the {@code .orsc} repack carries sectors
 * {@code maps64} does not (notably filled-in upper floors), so collision built from it diverges
 * from the server's. Pick deliberately: {@link #fromJag} to match a stock server,
 * {@link #fromOrsc} for a custom landscape. There is no automatic fallback between them; a missing
 * file is an error, never a silent switch to the other dataset.
 *
 * <p>Addressing is identical across both: {@code (floor 0..3, sectionX = worldX/48,
 * sectionY = worldZ/48)}, with tiles inside a sector at {@code lx * 48 + ly}.
 */
public interface LandscapeSource extends AutoCloseable {

  /**
   * {@code based_map_data} revision the authentic (Uranium) server uses — selects
   * {@code maps64.jag/.mem} + {@code land64.jag/.mem}. The default for {@link #fromJag(Path)} and
   * {@link #resolve(ServerConf)}.
   */
  int AUTHENTIC_MAP_REV = 64;

  /** System property naming the landscape to load; see {@link #resolve(ServerConf)}. */
  String LANDSCAPE_PROPERTY = "openrsc.landscape";

  /** Environment fallback for {@link #LANDSCAPE_PROPERTY}. */
  String LANDSCAPE_ENV = "OPENRSC_LANDSCAPE";

  /** System property overriding the JAG map revision; see {@link #resolve(ServerConf)}. */
  String MAP_REV_PROPERTY = "openrsc.mapRev";

  /** Environment fallback for {@link #MAP_REV_PROPERTY}. */
  String MAP_REV_ENV = "OPENRSC_MAP_REV";

  /**
   * Decode one sector, or {@code null} when this landscape has no data for it (the server leaves
   * such regions fully blocked; the renderer substitutes a blank sector).
   *
   * <p>Returns a fresh {@link RawSector} per call — callers are free to mutate it.
   */
  RawSector sector(int floor, int sectionX, int sectionY);

  /**
   * Whether this landscape holds data for the sector, without paying for the decode. Same condition
   * that makes {@link #sector} return non-null.
   */
  boolean exists(int floor, int sectionX, int sectionY);

  /** Short human-readable identification of the underlying files, for log lines. */
  String describe();

  /** Releases any held file handles. Unchecked — an unreadable close is not a caller concern. */
  @Override
  void close();

  /**
   * Open the classic JAG map archives under {@code mapsDir} at the authentic revision
   * ({@value #AUTHENTIC_MAP_REV}), overlaying members-only sectors.
   */
  static LandscapeSource fromJag(Path mapsDir) throws IOException {
    return fromJag(mapsDir, AUTHENTIC_MAP_REV, true);
  }

  /**
   * Open the classic JAG map archives.
   *
   * @param mapsDir     directory holding {@code maps{rev}.jag} (+ optional {@code .mem} overlay and
   *                    {@code land{rev}.*} height/colour archives)
   * @param rev         {@code based_map_data} revision (Uranium = {@value #AUTHENTIC_MAP_REV})
   * @param memberWorld overlay the members-only {@code .mem} sectors — members-only areas live
   *                    solely there, so {@code false} silently drops half the world
   * @throws IOException if {@code maps{rev}.jag} is absent or unreadable
   */
  static LandscapeSource fromJag(Path mapsDir, int rev, boolean memberWorld) throws IOException {
    JagLandscape jag = JagLandscape.open(mapsDir, rev, memberWorld);
    if (jag == null) {
      throw new IOException("No JAG landscape at " + mapsDir.resolve("maps" + rev + ".jag")
          + " (set -D" + LANDSCAPE_PROPERTY + " to point at a landscape, or -D" + MAP_REV_PROPERTY
          + " to pick another revision)");
    }
    return jag;
  }

  /**
   * Open an {@code .orsc} landscape ZIP ({@code Authentic_Landscape.orsc},
   * {@code Custom_Landscape.orsc}, …).
   *
   * @throws IOException if the file is absent or is not a readable ZIP
   */
  static LandscapeSource fromOrsc(Path orscFile) throws IOException {
    return new OrscLandscape(orscFile);
  }

  /**
   * The landscape this JVM was asked to use, defaulting to the server-authentic JAG archives
   * ({@code <conf>/data/maps/maps}{@value #AUTHENTIC_MAP_REV}{@code .jag}).
   *
   * <p>Override with {@code -Dopenrsc.landscape=<path>} (or {@code $OPENRSC_LANDSCAPE}); the
   * container is inferred from what the path names:
   * <ul>
   *   <li>{@code …/Custom_Landscape.orsc} — an {@code .orsc} ZIP</li>
   *   <li>{@code …/maps/maps63.jag} (or {@code .mem}) — JAG at the revision in the filename; the
   *       sibling {@code .mem}/{@code land63.*} archives are picked up automatically</li>
   *   <li>{@code …/maps} — a directory of JAG archives, at revision
   *       {@value #AUTHENTIC_MAP_REV} unless {@code -Dopenrsc.mapRev} says otherwise</li>
   * </ul>
   *
   * <p>Only entrypoints that are <em>meant</em> to be repointed (the map/asset bakes) should call
   * this. Code that must agree with the live server — the bot's collision map — calls
   * {@link #fromJag} directly, so no property can silently desynchronise it.
   */
  static LandscapeSource resolve(ServerConf conf) throws IOException {
    return resolve(conf, null);
  }

  /**
   * As {@link #resolve(ServerConf)}, but falling back to {@code world}'s own landscape rather than
   * the authentic JAG archives when no {@code -Dopenrsc.landscape} is set — so
   * {@code -Dopenrsc.world=rsccabbage} alone gets Cabbage's {@code Custom_Landscape.orsc}, while an
   * explicit {@code -Dopenrsc.landscape} still wins over both.
   *
   * @param world the world whose landscape to use by default; {@code null} for the authentic one.
   */
  static LandscapeSource resolve(ServerConf conf, openrsc.gamedata.WorldProfile world)
      throws IOException {
    String spec = setting(LANDSCAPE_PROPERTY, LANDSCAPE_ENV);
    if (spec == null) {
      if (world != null) {
        return world.openLandscape(conf);
      }
      return fromJag(conf.data().resolve("maps"), mapRev(), true);
    }
    Path path = Path.of(spec).toAbsolutePath().normalize();
    if (Files.isDirectory(path)) {
      return fromJag(path, mapRev(), true);
    }
    String name = path.getFileName().toString();
    if (name.regionMatches(true, name.length() - 5, ".orsc", 0, 5)) {
      return fromOrsc(path);
    }
    // maps64.jag, maps63.mem, … — the revision in the filename wins over -Dopenrsc.mapRev, since
    // naming the archive is the more specific instruction.
    Matcher m = Pattern.compile("maps(\\d+)\\.(?:jag|mem)", Pattern.CASE_INSENSITIVE).matcher(name);
    if (m.matches()) {
      return fromJag(path.getParent(), Integer.parseInt(m.group(1)), true);
    }
    throw new IOException("-D" + LANDSCAPE_PROPERTY + "=" + spec
        + " names neither an .orsc landscape, a maps<rev>.jag/.mem archive, nor a directory of "
        + "JAG archives");
  }

  /** {@code -Dopenrsc.mapRev} / {@code $OPENRSC_MAP_REV}, else the authentic revision. */
  private static int mapRev() throws IOException {
    String raw = setting(MAP_REV_PROPERTY, MAP_REV_ENV);
    if (raw == null) {
      return AUTHENTIC_MAP_REV;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IOException("-D" + MAP_REV_PROPERTY + "=" + raw + " is not a number");
    }
  }

  private static String setting(String property, String env) {
    String v = System.getProperty(property);
    if (v == null || v.isBlank()) {
      v = System.getenv(env);
    }
    return v == null || v.isBlank() ? null : v.trim();
  }
}
