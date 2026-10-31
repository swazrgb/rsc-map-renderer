package openrsc.gamedata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The key-value pairs of a server {@code .conf} file, parsed as the server's {@code YMLReader}
 * parses them: a line whose {@code #} comes before its {@code :} is commented out, a trailing
 * comment is dropped, a value may hold one {@code :} ({@code host:port}), section headers are
 * ignored, and the first occurrence of a key wins. The server differs in one case: a later
 * {@code null} or {@code host:port} line replaces an earlier one.
 *
 * <p>The typed getters accept what the server's readers accept: {@code true} or {@code false} in
 * any case, and Java's integer and double syntax.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/YMLReader.java#L16">YMLReader.loadFromYML</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L833">ServerConfiguration.tryReadBool</a>
 */
public final class ServerProperties {

    private final Map<String, String> settings;

    private ServerProperties(Map<String, String> settings) {
        this.settings = settings;
    }

    public static ServerProperties load(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    public static ServerProperties parse(List<String> lines) {
        Map<String, String> settings = new HashMap<>();
        for (String line : lines) {
            if (line.contains("#")) {
                if (line.split("#").length < 2) {
                    continue;
                }
                if (line.indexOf('#') < line.indexOf(':')) {
                    continue;
                }
                for (String piece : line.split("#")) {
                    if (piece.contains(":")) {
                        line = piece;
                        break;
                    }
                }
            }

            String[] elems = line.split(":");
            for (int i = 0; i < elems.length; i++) {
                elems[i] = elems[i].trim();
            }
            if (elems.length == 2) {
                settings.putIfAbsent(elems[0], elems[1]);
            } else if (elems.length == 3) {
                settings.putIfAbsent(elems[0], elems[1] + ":" + elems[2]);
            }
        }
        return new ServerProperties(settings);
    }

    public Optional<String> string(String key) {
        return Optional.ofNullable(settings.get(key));
    }

    /** Empty when the key is absent or its value is not a boolean. */
    public Optional<Boolean> bool(String key) {
        String v = settings.get(key);
        if (v == null) {
            return Optional.empty();
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Optional.of(Boolean.parseBoolean(v));
        }
        return Optional.empty();
    }

    /** {@code fallback} when the key is absent or its value is not a boolean. */
    public boolean bool(String key, boolean fallback) {
        return bool(key).orElse(fallback);
    }

    /** Empty when the key is absent or its value is not an integer. */
    public Optional<Integer> integer(String key) {
        String v = settings.get(key);
        if (v == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** {@code fallback} when the key is absent or its value is not an integer. */
    public int integer(String key, int fallback) {
        return integer(key).orElse(fallback);
    }

    /** {@code fallback} when the key is absent or its value is not a number. */
    public double decimal(String key, double fallback) {
        String v = settings.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
