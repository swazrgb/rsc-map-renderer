package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/**
 * A scenery. {@code typ} 1 blocks every tile of its {@code width × height} footprint, 2 puts a wall on
 * one edge of each, and any other value does not block.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L507">World.registerGameObject</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ObjectDef(
        @JsonProperty("ID") int id,
        @JsonProperty("Name") String name,
        @JsonProperty("Typ") int typ,
        @JsonProperty("Width") int width,
        @JsonProperty("Height") int height,
        @JsonProperty("Command1") String command1,
        @JsonProperty("Command2") String command2) {

    /**
     * Whether this is a ladder or staircase: its first verb climbs or goes up or down.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L27">Ladders.blockObjectAction</a>
     */
    public boolean isClimbable() {
        if (command1 == null) {
            return false;
        }
        String c = command1.toLowerCase(Locale.ROOT);
        return c.equals("climb-up")
                || c.equals("climb-down")
                || c.equals("climb up")
                || c.equals("climb down")
                || c.equals("go up")
                || c.equals("go down");
    }
}
