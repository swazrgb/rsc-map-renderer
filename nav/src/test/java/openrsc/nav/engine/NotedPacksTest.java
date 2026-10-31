package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.container.NoteForm;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.Manifest;
import openrsc.nav.Packs;
import openrsc.nav.RealDefs;
import openrsc.nav.eligibility.MeleeWeapons;
import org.junit.jupiter.api.Test;

class NotedPacksTest {

    private static final ServerData DEFS = RealDefs.serverData();
    private static final int IRON = ItemId.IRON_ORE.id();

    private static Inventory pack(InventorySlot... slots) {
        MutableInventory m = new MutableInventory();
        for (InventorySlot s : slots) {
            m.setSlot(s.slot(), s);
        }
        return m.snapshot();
    }

    private static InventorySlot notes(int slot, int amount) {
        return new InventorySlot(slot, IRON, amount, false, true);
    }

    private static Bank bank(int id, int amount) {
        MutableBank m = new MutableBank();
        if (amount > 0) {
            m.setSlot(0, new BankSlot(0, id, amount));
        }
        return m.snapshot();
    }

    @Test
    void a_stop_banks_the_notes_and_draws_the_item() {
        Manifest stop = new Manifest(Map.of(ItemId.IRON_ORE, 14L), Map.of(), Set.of(), Map.of(ItemId.IRON_ORE, 0L));
        Inventory leaving = Packs.apply(pack(notes(0, 300)), stop, DEFS);
        assertEquals(14, leaving.count(IRON), leaving.slots().toString());
        assertEquals(14, leaving.count(NoteForm.UNNOTED, IRON));
        assertEquals(0, leaving.count(NoteForm.NOTED, IRON));
        assertEquals(14, leaving.usedSlots());
    }

    @Test
    void a_grant_lands_as_the_item_beside_the_notes() {
        Inventory both = pack(new InventorySlot(0, IRON, 1, false), notes(1, 300));
        Inventory granted = Packs.adjust(both, Map.of(IRON, 2L), Map.of(), DEFS);
        assertEquals(3, granted.count(NoteForm.UNNOTED, IRON), granted.slots().toString());
        assertEquals(300, granted.count(NoteForm.NOTED, IRON));
        assertEquals(4, granted.usedSlots(), "three of the item and one noted stack: " + granted.slots());
    }

    @Test
    void notes_spent_stay_one_stack() {
        Inventory spent = Packs.consume(pack(notes(0, 300)), Map.of(IRON, 100L), DEFS);
        assertEquals(200, spent.count(NoteForm.BOTH, IRON));
        assertEquals(200, spent.count(NoteForm.NOTED, IRON));
        assertEquals(0, spent.count(IRON), "the item itself: none");
        assertEquals(1, spent.usedSlots(), spent.slots().toString());
    }

    @Test
    void a_fare_is_its_own_band_beside_a_noted_one() {
        LoadoutSpec cargo =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.IRON_ORE, 300, 300, Long.MAX_VALUE, false, true)));
        LoadoutSpec folded = Engine.withFares(cargo, Map.of(IRON, 5L));
        assertEquals(
                List.of(
                        new LoadoutSpec.Item(ItemId.IRON_ORE, 300, 300, Long.MAX_VALUE, false, true),
                        new LoadoutSpec.Item(ItemId.IRON_ORE, 5, 5, 5, false)),
                folded.requirements(),
                "a fare is paid in the item, never in notes");
    }

    @Test
    void the_fare_float_counts_noted_fare_stock() {
        int rope = ItemId.ROPE.id();
        Inventory noted = pack(new InventorySlot(0, rope, 3, false, true));
        Inventory floated = Engine.fareFloat(pack(), noted, bank(rope, 0), List.of(rope), DEFS);
        assertEquals(3, floated.count(rope), "three ropes the stop can draw");
    }

    @Test
    void a_noted_weapon_is_no_melee_weapon() {
        int sword = ItemId.BRONZE_SHORT_SWORD.id();
        assertFalse(MeleeWeapons.carried(pack(new InventorySlot(0, sword, 5, false, true)), DEFS));
        assertTrue(MeleeWeapons.carried(pack(new InventorySlot(0, sword, 1, false)), DEFS));
    }
}
