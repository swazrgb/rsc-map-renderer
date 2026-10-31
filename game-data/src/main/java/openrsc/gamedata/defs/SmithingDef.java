package openrsc.gamedata.defs;

/**
 * {@code bars} smithed into {@code amount} of {@code itemId}.
 */
public record SmithingDef(int requiredLevel, int bars, int itemId, int amount) {}
