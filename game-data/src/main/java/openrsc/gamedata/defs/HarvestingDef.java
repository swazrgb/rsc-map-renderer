package openrsc.gamedata.defs;

/**
 * {@code exhaust} is the percent chance a harvest exhausts the scenery; {@code respawnTime} is in
 * seconds.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/skills/harvesting/Harvesting.java#L430">Harvesting.batchHarvest</a>
 */
public record HarvestingDef(int requiredLevel, int produceId, int exp, int exhaust, int respawnTime) {}
