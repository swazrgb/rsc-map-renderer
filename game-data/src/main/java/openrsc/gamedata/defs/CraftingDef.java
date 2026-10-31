package openrsc.gamedata.defs;

/**
 * {@code gemId} is the gem the piece takes, or -1 for plain gold.
 */
public record CraftingDef(int requiredLevel, int itemId, int exp, int gemId) {}
