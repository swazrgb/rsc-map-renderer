package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A ground overlay. A tile whose overlay has a non-zero {@code objectType} cannot be walked on;
 * {@code colour} and {@code tileValue} are for drawing it.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L386">WorldLoader.loadSection</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TileDef(
        @JsonProperty("ObjectType") int objectType,
        @JsonProperty("Colour") int colour,
        @JsonProperty("TileValue") int tileValue) {}
