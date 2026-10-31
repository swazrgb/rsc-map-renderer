package openrsc.bot.api.container;

import openrsc.gamedata.data.ItemRef;

/**
 * An account's inventory and bank from the same moment, counted together. A form-less query counts
 * the item itself ({@link NoteForm#UNNOTED}); with a {@link NoteForm}, the inventory counts that form
 * and the bank, which holds no notes, counts in full except for {@link NoteForm#NOTED}.
 */
public record Holdings(Inventory inventory, Bank bank) implements ItemCount {

    public static final Holdings EMPTY = new Holdings(Inventory.empty(), Bank.empty());

    public long count(NoteForm form, int itemId) {
        long carried = inventory.count(form, itemId);
        return form == NoteForm.NOTED ? carried : carried + bank.count(itemId);
    }

    public long count(NoteForm form, ItemRef item) {
        return count(form, item.id());
    }

    @Override
    public long count(int itemId) {
        return count(NoteForm.UNNOTED, itemId);
    }

    @Override
    public long count(ItemRef item) {
        return count(NoteForm.UNNOTED, item.id());
    }

    public boolean has(NoteForm form, int itemId) {
        return inventory.has(form, itemId) || (form != NoteForm.NOTED && bank.has(itemId));
    }

    public boolean has(NoteForm form, ItemRef item) {
        return has(form, item.id());
    }

    public boolean has(NoteForm form, int itemId, long amount) {
        if (amount <= 0) {
            return true;
        }
        long carried = inventory.count(form, itemId);
        return carried >= amount || (form != NoteForm.NOTED && carried + bank.count(itemId) >= amount);
    }

    @Override
    public boolean has(int itemId) {
        return has(NoteForm.UNNOTED, itemId);
    }

    @Override
    public boolean has(ItemRef item) {
        return has(NoteForm.UNNOTED, item.id());
    }

    @Override
    public boolean has(ItemRef item, long amount) {
        return has(NoteForm.UNNOTED, item.id(), amount);
    }
}
