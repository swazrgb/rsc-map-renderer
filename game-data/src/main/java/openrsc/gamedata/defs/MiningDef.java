package openrsc.gamedata.defs;

/**
 * {@code depletion} is the percent chance an ore empties the rock, rolled only on a world with
 * {@code mining_rocks_extended}; elsewhere every ore empties it. {@code respawnTime} is in seconds.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/mining/Mining.java#L291">Mining.batchMining</a>
 */
public record MiningDef(int requiredLevel, int oreId, int exp, int depletion, int respawnTime) {}
