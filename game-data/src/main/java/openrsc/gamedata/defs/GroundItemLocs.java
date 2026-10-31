package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Reader for the server's {@code GroundItems*.json}: the items the server places on the ground at
 * start-up and puts back {@code respawn} seconds after one is taken.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L365">WorldPopulator.loadItemLocs</a>
 */
public final class GroundItemLocs {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pos(
            @JsonProperty("X") int x, @JsonProperty("Y") int z) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spawn(
            @JsonProperty("id") int id,
            @JsonProperty("pos") Pos pos,
            @JsonProperty("amount") int amount,
            @JsonProperty("respawn") int respawn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(@JsonProperty("grounditems") List<Spawn> grounditems) {}

    /** Every spawn in {@code file}, in file order. */
    public static List<Spawn> load(Path file) throws IOException {
        ObjectMapper m = new ObjectMapper();
        Root root = m.readValue(Files.readAllBytes(file), Root.class);
        return root.grounditems();
    }

    private GroundItemLocs() {}
}
