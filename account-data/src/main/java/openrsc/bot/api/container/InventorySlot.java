package openrsc.bot.api.container;

import openrsc.gamedata.data.ItemId;

/**
 * An inventory slot. {@code equipped} marks a worn item; {@code noted} marks a bank note of
 * {@code item}, which carries the item's own id.
 */
public record InventorySlot(int slot, ItemId item, int amount, boolean equipped, boolean noted) implements Slot {

    public InventorySlot(int slot, ItemId item, int amount, boolean equipped) {
        this(slot, item, amount, equipped, false);
    }

    public InventorySlot(int slot, int itemId, int amount, boolean equipped, boolean noted) {
        this(slot, ItemId.getById(itemId), amount, equipped, noted);
    }

    public InventorySlot(int slot, int itemId, int amount, boolean equipped) {
        this(slot, ItemId.getById(itemId), amount, equipped, false);
    }
}
