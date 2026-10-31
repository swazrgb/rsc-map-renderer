package openrsc.gamedata.defs;

/**
 * {@code length}: how long the fire burns, in seconds.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/FiremakingDef.java#L21">FiremakingDef.getLength</a>
 */
public record FiremakingDef(int requiredLevel, int exp, int length) {}
