package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Reader for the server's {@code NpcLocs*.json}: each NPC spawn, its start tile and the rectangle
 * {@code [min, max]} it wanders in.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L330">WorldPopulator.loadNpcLocs</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L164">NpcBehavior.handleRoam</a>
 */
public final class NpcLocs {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pos(
            @JsonProperty("X") int x, @JsonProperty("Y") int z) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spawn(
            @JsonProperty("id") int id,
            @JsonProperty("start") Pos start,
            @JsonProperty("min") Pos min,
            @JsonProperty("max") Pos max) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(@JsonProperty("npclocs") List<Spawn> npclocs) {}

    /** Every spawn in {@code file}, in file order: the order the server registers them in. */
    public static List<Spawn> load(Path file) throws IOException {
        ObjectMapper m = new ObjectMapper();
        Root root = m.readValue(Files.readAllBytes(file), Root.class);
        return root.npclocs();
    }

    private NpcLocs() {}
}
