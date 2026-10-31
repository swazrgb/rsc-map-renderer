package openrsc.gamedata.defs;

/**
 * {@code fell} is the percent chance a log fells the tree; {@code respawnTime} is in seconds.
 * {@code low} and {@code high} hold, per {@link WoodcuttingDefs#AXE_TIERS axe}, the success chance
 * out of 256 at level 1 and at level 99, interpolated between.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/Woodcutting.java#L124">Woodcutting.batchWoodcutting</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L369">Formulae.interp</a>
 */
public record WoodcuttingDef(int requiredLevel, int logId, int exp, int fell, int respawnTime, int[] low, int[] high) {}
