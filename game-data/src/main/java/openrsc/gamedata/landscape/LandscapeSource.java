package openrsc.gamedata.landscape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;

/**
 * Decoded landscape sectors, from either container the server can load: the JAG map archives
 * ({@link #fromJag}) or an {@code .orsc} ZIP ({@link #fromOrsc}). The server reads the JAG
 * archives whenever they open, unless {@code want_custom_landscape} is set, and the {@code .orsc}
 * file only when neither opens; the two datasets differ, so a collision map that must agree with a
 * server is built from the one that server loads.
 *
 * <p>Sectors are addressed as the server's loader addresses them: {@code floor} 0..3 and archive
 * section coordinates, {@code sectionX = worldX / 48 + 48} and
 * {@code sectionY = floorLocalY / 48 + 37}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L456">WorldLoader.loadWorld</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L529">WorldLoader.loadWorld</a>
 */
public interface LandscapeSource extends AutoCloseable {

    /**
     * The map revision ({@code based_map_data}) {@link #resolve} assumes for a directory when
     * {@link #MAP_REV_PROPERTY} is unset.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/uranium.conf#L74">based_map_data</a>
     */
    int AUTHENTIC_MAP_REV = 64;

    /** System property naming the landscape {@link #resolve} loads. */
    String LANDSCAPE_PROPERTY = "openrsc.landscape";

    /** Environment variable read when {@link #LANDSCAPE_PROPERTY} is unset or blank. */
    String LANDSCAPE_ENV = "OPENRSC_LANDSCAPE";

    /** System property giving the map revision for a {@link #LANDSCAPE_PROPERTY} directory. */
    String MAP_REV_PROPERTY = "openrsc.mapRev";

    /** Environment variable read when {@link #MAP_REV_PROPERTY} is unset or blank. */
    String MAP_REV_ENV = "OPENRSC_MAP_REV";

    /**
     * The JAG archives {@code maps<rev>.jag} in {@code mapsDir}, with the {@code .mem} and
     * {@code land<rev>} archives beside it when present. {@code memberWorld} lays the members'
     * {@code .mem} sectors over the free ones; members-only areas exist only there.
     *
     * @throws IOException when {@code maps<rev>.jag} is absent or unreadable
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

    /** @throws IOException when {@code orscFile} is absent or not a readable ZIP */
    static LandscapeSource fromOrsc(Path orscFile) throws IOException {
        return new OrscLandscape(orscFile);
    }

    /**
     * The landscape {@link #LANDSCAPE_PROPERTY} names, or {@code world}'s own when it is unset. The
     * path may be an {@code .orsc} file, a {@code maps<rev>.jag} or {@code .mem} archive (at the
     * revision in its name) or a directory of JAG archives (at {@link #MAP_REV_PROPERTY}, default
     * {@link #AUTHENTIC_MAP_REV}); a JAG landscape named this way always includes members' sectors.
     *
     * @throws IOException when the path is none of these, or what it names cannot be opened
     */
    static LandscapeSource resolve(ServerConf conf, WorldProfile world) throws IOException {
        String spec = setting(LANDSCAPE_PROPERTY, LANDSCAPE_ENV);
        if (spec == null) {
            return world.openLandscape(conf);
        }
        Path path = Path.of(spec).toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return fromJag(path, mapRev(), true);
        }
        String name = path.getFileName().toString();
        if (name.regionMatches(true, name.length() - 5, ".orsc", 0, 5)) {
            return fromOrsc(path);
        }
        Matcher m = Pattern.compile("maps(\\d+)\\.(?:jag|mem)", Pattern.CASE_INSENSITIVE)
                .matcher(name);
        if (m.matches()) {
            return fromJag(path.getParent(), Integer.parseInt(m.group(1)), true);
        }
        throw new IOException("-D" + LANDSCAPE_PROPERTY + "=" + spec
                + " names neither an .orsc landscape, a maps<rev>.jag/.mem archive, nor a directory of "
                + "JAG archives");
    }

    /**
     * The decoded sector, a fresh instance each call, or {@code null} when the landscape has none
     * there; the server leaves such a sector's tiles fully blocked.
     *
     * @throws java.io.UncheckedIOException when the sector is present but cannot be read
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/region/TileValue.java#L6">TileValue.traversalMask</a>
     */
    RawSector sector(int floor, int sectionX, int sectionY);

    /** Whether {@link #sector} would return a sector here, without decoding it. */
    boolean exists(int floor, int sectionX, int sectionY);

    /** The container and file this reads, for log lines. */
    String describe();

    @Override
    void close();

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
