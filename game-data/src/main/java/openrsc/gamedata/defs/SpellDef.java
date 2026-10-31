package openrsc.gamedata.defs;

/**
 * {@code id}: the spell's index in {@code SpellDef.xml}, which is what the server and client call
 * it by.
 */
public record SpellDef(int id, String name, int requiredLevel) {}
