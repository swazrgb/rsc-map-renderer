package openrsc.bot.api.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntIterator;
import java.util.List;
import openrsc.gamedata.data.ItemId;
import org.junit.jupiter.api.Test;

class NoteFormTest {

    private static final int SHARK = ItemId.SHARK.id();
    private static final int LOBSTER = ItemId.LOBSTER.id();
    private static final int SWORD = ItemId.BRONZE_SHORT_SWORD.id();

    private static InventorySlot plain(int slot, int id, int amount) {
        return new InventorySlot(slot, id, amount, false);
    }

    private static InventorySlot notes(int slot, int id, int amount) {
        return new InventorySlot(slot, id, amount, false, true);
    }

    private static final Inventory MIXED = Inventory.of(List.of(plain(0, SHARK, 1), notes(1, SHARK, 50)));

    private static final Inventory NOTES_ONLY = Inventory.of(List.of(notes(0, SHARK, 50)));

    @Test
    void count_is_the_item_itself_and_a_form_names_the_rest() {
        assertEquals(1, MIXED.count(SHARK));
        assertEquals(1, MIXED.count(ItemId.SHARK));
        assertEquals(1, MIXED.count(NoteForm.UNNOTED, SHARK));
        assertEquals(50, MIXED.count(NoteForm.NOTED, SHARK));
        assertEquals(51, MIXED.count(NoteForm.BOTH, SHARK));

        assertEquals(0, NOTES_ONLY.count(SHARK));
        assertEquals(50, NOTES_ONLY.count(NoteForm.BOTH, SHARK));
    }

    @Test
    void has_is_the_item_itself_and_a_form_names_the_rest() {
        assertTrue(MIXED.has(SHARK));
        assertTrue(MIXED.has(ItemId.SHARK));
        assertTrue(MIXED.has(NoteForm.UNNOTED, SHARK));
        assertTrue(MIXED.has(NoteForm.NOTED, SHARK));
        assertFalse(MIXED.has(ItemId.SHARK, 2), "one shark to eat, not two");

        assertFalse(NOTES_ONLY.has(SHARK), "notes are nothing to eat");
        assertFalse(NOTES_ONLY.has(ItemId.SHARK));
        assertFalse(NOTES_ONLY.has(NoteForm.UNNOTED, SHARK));
        assertTrue(NOTES_ONLY.has(NoteForm.NOTED, SHARK));
        assertTrue(NOTES_ONLY.has(NoteForm.BOTH, SHARK));
    }

    @Test
    void count_any_takes_a_form_too() {
        Inventory pack = Inventory.of(List.of(plain(0, LOBSTER, 1), notes(1, SHARK, 50), plain(2, SHARK, 1)));
        assertEquals(2, pack.countAny(SHARK, LOBSTER));
        assertEquals(2, pack.countAny(ItemId.SHARK, ItemId.LOBSTER));
        assertEquals(50, pack.countAny(NoteForm.NOTED, SHARK, LOBSTER));
        assertEquals(52, pack.countAny(NoteForm.BOTH, ItemId.SHARK, ItemId.LOBSTER));
    }

    @Test
    void first_of_and_index_of_answer_the_form_asked() {
        assertEquals(0, MIXED.firstOf(SHARK).slot());
        assertEquals(0, MIXED.firstOf(ItemId.SHARK).slot());
        assertEquals(1, MIXED.firstOf(NoteForm.NOTED, SHARK).slot());
        assertEquals(0, MIXED.firstOf(NoteForm.BOTH, SHARK).slot());

        Inventory notesFirst = Inventory.of(List.of(notes(0, SHARK, 50), plain(1, SHARK, 1)));
        assertEquals(1, notesFirst.firstOf(SHARK).slot(), "the shark to eat, past the notes");
        assertEquals(1, notesFirst.indexOf(SHARK));
        assertEquals(0, notesFirst.indexOf(NoteForm.BOTH, SHARK));
        assertEquals(0, notesFirst.firstOf(NoteForm.NOTED, ItemId.SHARK).slot());
        assertEquals(0, notesFirst.firstOf(NoteForm.BOTH, SHARK).slot());

        assertNull(NOTES_ONLY.firstOf(SHARK));
        assertNull(NOTES_ONLY.firstOf(ItemId.SHARK));
        assertEquals(-1, NOTES_ONLY.indexOf(SHARK));
        assertEquals(0, NOTES_ONLY.firstOf(NoteForm.NOTED, SHARK).slot());
        assertEquals(0, NOTES_ONLY.firstOf(NoteForm.BOTH, SHARK).slot());
        assertNull(MIXED.firstOf(NoteForm.NOTED, LOBSTER));
    }

    @Test
    void worn_kit_is_its_own_axis() {
        Inventory pack = Inventory.of(
                List.of(new InventorySlot(0, SWORD, 1, true), notes(1, SWORD, 4), plain(2, SWORD, 1)), true);
        assertEquals(2, pack.count(SWORD), "the worn sword and the loose one");
        assertEquals(1, pack.unequippedCount(SWORD));
        assertEquals(5, pack.unequippedCount(NoteForm.BOTH, SWORD), "the loose one and the notes: givable");
        assertEquals(4, pack.unequippedCount(NoteForm.NOTED, SWORD));
        assertEquals(1, pack.packCount(SWORD), "the worn one is off the pack");
        assertEquals(5, pack.packCount(NoteForm.BOTH, SWORD));
        assertEquals(2, pack.firstLoose(SWORD).slot());
        assertEquals(1, pack.firstLoose(NoteForm.NOTED, SWORD).slot());
        assertEquals(1, pack.firstLoose(NoteForm.BOTH, SWORD).slot());
    }

    @Test
    void owned_follows_the_packs_rule_and_the_bank_counts_in_full() {
        MutableBank bank = new MutableBank();
        bank.setSlot(0, new BankSlot(0, SHARK, 100));
        Holdings owned = new Holdings(MIXED, bank.snapshot());
        assertEquals(101, owned.count(SHARK), "the shark itself, aboard and banked");
        assertEquals(101, owned.count(ItemId.SHARK));
        assertEquals(50, owned.count(NoteForm.NOTED, SHARK), "the bank holds no notes");
        assertEquals(151, owned.count(NoteForm.BOTH, ItemId.SHARK), "stock: the notes too");
        assertTrue(owned.has(ItemId.SHARK, 101));
        assertFalse(owned.has(ItemId.SHARK, 102));
        assertTrue(owned.has(NoteForm.BOTH, SHARK, 151));
        assertFalse(owned.has(NoteForm.NOTED, SHARK, 51));

        Holdings notesOnly = new Holdings(NOTES_ONLY, Bank.empty());
        assertFalse(notesOnly.has(SHARK), "notes are nothing to use, anywhere");
        assertTrue(notesOnly.has(NoteForm.BOTH, SHARK));
    }

    @Test
    void without_notes_every_form_agrees() {
        Inventory pack = Inventory.of(List.of(
                plain(0, SHARK, 1),
                new InventorySlot(1, SWORD, 1, true),
                plain(2, LOBSTER, 1),
                plain(3, SHARK, 1),
                plain(4, ItemId.COINS.id(), 500)));
        for (IntIterator it = pack.itemIds().iterator(); it.hasNext(); ) {
            int id = it.nextInt();
            assertEquals(pack.count(NoteForm.BOTH, id), pack.count(id));
            assertEquals(pack.count(NoteForm.UNNOTED, id), pack.count(id));
            assertEquals(0, pack.count(NoteForm.NOTED, id));
            assertEquals(pack.indexOf(NoteForm.BOTH, id), pack.indexOf(id));
            assertEquals(pack.firstOf(NoteForm.BOTH, id), pack.firstOf(id));
            assertEquals(pack.unequippedCount(NoteForm.BOTH, id), pack.unequippedCount(id));
            assertEquals(pack.packCount(NoteForm.BOTH, id), pack.packCount(id));
            assertNull(pack.firstOf(NoteForm.NOTED, id));
        }
        assertEquals(2, pack.count(SHARK));
    }
}
