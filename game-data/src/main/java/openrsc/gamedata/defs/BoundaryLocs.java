package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Reader for the server's {@code BoundaryLocs*.json}: the boundaries placed on tile edges. A
 * placement's {@code direction}, 0 to 3, picks the edge or diagonal it stands on.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L393">WorldPopulator.loadGameObjLocs</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L554">World.registerGameObject</a>
 */
public final class BoundaryLocs {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pos(
            @JsonProperty("X") int x, @JsonProperty("Y") int y) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Loc(
            @JsonProperty("id") int id,
            @JsonProperty("pos") Pos pos,
            @JsonProperty("direction") int direction) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(@JsonProperty("boundaries") List<Loc> boundaries) {}

    /** Every placement in {@code file}, in file order. */
    public static List<Loc> load(Path file) throws IOException {
        ObjectMapper m = new ObjectMapper();
        Root root = m.readValue(Files.readAllBytes(file), Root.class);
        return root.boundaries();
    }

    private BoundaryLocs() {}
}
