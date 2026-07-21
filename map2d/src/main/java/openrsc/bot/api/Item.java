package openrsc.bot.api;

import openrsc.gamedata.ItemRef;

/**
 * Snapshot of an inventory slot.
 *
 * @param slot     0–29, position in the player's inventory grid
 * @param id       item definition id (matches {@code ItemDefs.json})
 * @param amount   stack count; 1 for non-stackable items
 * @param equipped true if the item is currently worn
 */
public record Item(int slot, int id, int amount, boolean equipped) implements ItemRef {

}
