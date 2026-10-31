package openrsc.bot.api.container;

import openrsc.gamedata.data.ItemId;

public record BankSlot(int slot, ItemId item, int amount) implements Slot {

    public BankSlot(int slot, int itemId, int amount) {
        this(slot, ItemId.getById(itemId), amount);
    }
}
