package openrsc.bot.core.defs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code SceneryLocs.json} loader — every fixed scenery placement in the world. Mirrors plutonium's
 * {@code world.go:248-269}. Pairs with {@link ObjectDefs} to stamp scenery blocks onto the static
 * collision map.
 */
public final class SceneryLocs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Pos(@JsonProperty("X") int x, @JsonProperty("Y") int y) {

  }

  // Different OpenRSC loc files use PascalCase vs lowercase. Accept both.
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Loc(@JsonAlias({"ID", "id"}) int id,
                    @JsonAlias({"Pos", "pos"}) Pos pos,
                    @JsonAlias({"Direction", "direction"}) int direction) {

  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Root(@JsonAlias({"Sceneries", "sceneries"}) List<Loc> sceneries) {

  }

  public static List<Loc> load(Path file) throws IOException {
    ObjectMapper m = new ObjectMapper();
    Root root = m.readValue(Files.readAllBytes(file), Root.class);
    return root.sceneries();
  }

  private SceneryLocs() {
  }
}
