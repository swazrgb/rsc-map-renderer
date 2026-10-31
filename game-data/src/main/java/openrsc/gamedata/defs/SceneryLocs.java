package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Reader for the server's {@code SceneryLocs*.json}: the sceneries placed in the world, each at its
 * anchor tile, facing {@code direction}. The files spell their keys in either case.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L393">WorldPopulator.loadGameObjLocs</a>
 */
public final class SceneryLocs {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pos(
            @JsonProperty("X") int x, @JsonProperty("Y") int y) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Loc(
            @JsonAlias({"ID", "id"}) int id,
            @JsonAlias({"Pos", "pos"}) Pos pos,
            @JsonAlias({"Direction", "direction"}) int direction) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(
            @JsonAlias({"Sceneries", "sceneries"}) List<Loc> sceneries) {}

    /** Every placement in {@code file}, in file order. */
    public static List<Loc> load(Path file) throws IOException {
        ObjectMapper m = new ObjectMapper();
        Root root = m.readValue(Files.readAllBytes(file), Root.class);
        return root.sceneries();
    }

    private SceneryLocs() {}
}
