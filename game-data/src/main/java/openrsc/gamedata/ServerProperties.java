package openrsc.gamedata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads an OpenRSC server {@code .conf} file ({@code uranium.conf}, {@code rsccabbage.conf}, …).
 *
 * <p>A literal port of the server's {@code util/YMLReader}, quirks included, so a key resolves here
 * exactly as it does for the server that will run the file:
 * <ul>
 *   <li>a line whose {@code '#'} precedes its {@code ':'} is a commented-out property — skipped</li>
 *   <li>a line that yields fewer than two {@code '#'}-separated pieces is comment-only — skipped</li>
 *   <li>otherwise the first {@code '#'}-separated piece containing a {@code ':'} is the property,
 *       so trailing comments fall away</li>
 *   <li>the property splits on {@code ':'}; three pieces re-join the last two (the {@code host:port}
 *       case)</li>
 *   <li><b>first</b> occurrence of a key wins; later duplicates are ignored</li>
 * </ul>
 *
 * <p>Section headers ({@code custom_features:}) split into a single piece and are therefore dropped,
 * which is why the flat key space works: every key in the file is unique regardless of section.
 */
public final class ServerProperties {

  private final Map<String, String> settings;

  private ServerProperties(Map<String, String> settings) {
    this.settings = settings;
  }

  /** Parse a {@code .conf} file. */
  public static ServerProperties load(Path file) throws IOException {
    return parse(Files.readAllLines(file));
  }

  /** Parse already-read {@code .conf} lines (see the class javadoc for the exact rules). */
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

  public boolean has(String key) {
    return settings.containsKey(key);
  }

  public Optional<String> string(String key) {
    return Optional.ofNullable(settings.get(key));
  }

  /** {@code true}/{@code false} case-insensitively; empty when absent or malformed. */
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

  public boolean bool(String key, boolean fallback) {
    return bool(key).orElse(fallback);
  }

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

  public int integer(String key, int fallback) {
    return integer(key).orElse(fallback);
  }
}
