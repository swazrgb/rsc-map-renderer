package openrsc.gamedata.defs;

/**
 * {@code secondId} added to {@code unfinishedId} makes {@code potionId}.
 */
public record HerbSecondDef(int secondId, int unfinishedId, int potionId, int requiredLevel, int exp) {}
