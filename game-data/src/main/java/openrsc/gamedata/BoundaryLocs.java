package openrsc.gamedata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code BoundaryLocs.json} loader — static wall/fence/door placements. Mirrors what the OpenRSC
 * server applies in {@code World.registerGameObject} for {@code BOUNDARY} objects: direction
 * selects which wall flag to set.
 */
public final class BoundaryLocs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Pos(@JsonProperty("X") int x, @JsonProperty("Y") int y) {

  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Loc(@JsonProperty("id") int id,
                    @JsonProperty("pos") Pos pos,
                    @JsonProperty("direction") int direction) {

  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Root(@JsonProperty("boundaries") List<Loc> boundaries) {

  }

  public static List<Loc> load(Path file) throws IOException {
    ObjectMapper m = new ObjectMapper();
    Root root = m.readValue(Files.readAllBytes(file), Root.class);
    return root.boundaries();
  }

  private BoundaryLocs() {
  }
}
