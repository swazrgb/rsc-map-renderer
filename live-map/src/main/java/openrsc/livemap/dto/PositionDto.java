package openrsc.livemap.dto;

/**
 * A tile: {@code x} and {@code z} are the server's x and y, whose y counts 944 per floor; {@code floor} is 0 to 3.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L471">Formulae.getHeight</a>
 */
public record PositionDto(int x, int z, int floor) {}
