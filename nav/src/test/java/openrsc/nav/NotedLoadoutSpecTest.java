package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.container.NoteForm;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.LoadoutSpec.Item;
import org.junit.jupiter.api.Test;

class NotedLoadoutSpecTest {

    private static final ServerData DEFS = RealDefs.serverData();
    private static final int IRON = ItemId.IRON_ORE.id();
    private static final int COAL = ItemId.COAL.id();

    // Rows are {id, amount, noted}, with noted 1 for a stack of bank notes.
    private static Inventory pack(int[]... slots) {
        MutableInventory m = new MutableInventory();
        for (int i = 0; i < slots.length; i++) {
            m.setSlot(
                    i, new InventorySlot(i, slots[i][0], slots[i][1], false, slots[i].length > 2 && slots[i][2] == 1));
        }
        return m.snapshot();
    }

    private static Bank bank(int[]... slots) {
        MutableBank m = new MutableBank();
        for (int i = 0; i < slots.length; i++) {
            m.setSlot(i, new BankSlot(i, slots[i][0], slots[i][1]));
        }
        return m.snapshot();
    }

    private static int[] plain(int id, int amount) {
        return new int[] {id, amount, 0};
    }

    private static int[] notes(int id, int amount) {
        return new int[] {id, amount, 1};
    }

    private static Item plainBand(long min, long target, long max) {
        return new Item(ItemId.IRON_ORE, min, target, max, false);
    }

    private static Item notedBand(long min, long target) {
        return new Item(ItemId.IRON_ORE, min, target, Long.MAX_VALUE, false, true);
    }

    @Test
    void under_anything_only_the_notes_a_band_draws_come_back_out() {
        LoadoutSpec spec = new LoadoutSpec(List.of(plainBand(1, 1, 1)), false, true);
        Settle settle = spec.settleAt(pack(notes(IRON, 50)), bank(), DEFS);
        assertTrue(settle.satisfiable(), settle.manifest().toString());
        assertEquals(
                1,
                settle.projected().count(NoteForm.UNNOTED, IRON),
                settle.manifest().toString());
        assertEquals(49, settle.projected().count(NoteForm.NOTED, IRON), "the rest stay aboard as notes");

        Settle strict = new LoadoutSpec(List.of(plainBand(1, 1, 1))).settleAt(pack(notes(IRON, 50)), bank(), DEFS);
        assertEquals(0, strict.projected().count(NoteForm.NOTED, IRON), "notes no band names bank");
    }

    @Test
    void a_noted_band_leaves_a_plain_bands_min_in_the_bank() {
        LoadoutSpec spec = new LoadoutSpec(List.of(
                new LoadoutSpec.Item(ItemId.COAL, 0, 200, Long.MAX_VALUE, false, true),
                new LoadoutSpec.Item(ItemId.COAL, 2, 2, 2, false)));
        Settle settle = spec.settleAt(pack(), bank(plain(ItemId.COAL.id(), 100)), DEFS);
        assertTrue(settle.satisfiable(), settle.manifest().toString());
        assertEquals(2, settle.projected().count(NoteForm.UNNOTED, ItemId.COAL.id()));
        assertEquals(98, settle.projected().count(NoteForm.NOTED, ItemId.COAL.id()));
    }

    @Test
    void a_plain_and_a_noted_band_draw_on_one_bank_stock() {
        LoadoutSpec spec = new LoadoutSpec(List.of(
                new LoadoutSpec.Item(ItemId.COAL, 0, 2, 3, false),
                new LoadoutSpec.Item(ItemId.COAL, 0, 200, Long.MAX_VALUE, false, true)));
        Settle settle = spec.settleAt(pack(), bank(plain(ItemId.COAL.id(), 2)), DEFS);
        assertEquals(
                2,
                settle.projected().count(NoteForm.BOTH, ItemId.COAL.id()),
                "two coal banked, two drawn between the forms: " + settle.manifest());
    }

    @Test
    void a_plain_band_never_counts_a_note_and_a_noted_band_never_counts_the_item() {
        LoadoutSpec work = new LoadoutSpec(List.of(plainBand(1, 14, 14)));
        assertFalse(work.satisfiedBy(pack(notes(IRON, 300))), "300 notes are no ore to smelt");
        assertTrue(work.satisfiedBy(pack(plain(IRON, 1))));
        assertFalse(work.minsMetBy(pack(notes(IRON, 300))));

        LoadoutSpec cargo = new LoadoutSpec(List.of(notedBand(300, 300)));
        assertTrue(cargo.satisfiedBy(pack(notes(IRON, 300))));
        assertFalse(cargo.satisfiedBy(pack(plain(IRON, 1))), "one ore is not the noted cargo");
    }

    @Test
    void notes_aboard_ride_a_catch_all() {
        assertTrue(LoadoutSpec.any().satisfiedBy(pack(notes(IRON, 300))), "a delivery walk carries them");
    }

    @Test
    void a_donor_withdraws_its_whole_cargo_as_one_noted_stack() {
        Settle s = new LoadoutSpec(List.of(notedBand(300, 300))).settleAt(pack(), bank(plain(IRON, 500)), DEFS);
        assertTrue(s.satisfiable());
        assertEquals(Map.of(ItemId.IRON_ORE, 300L), s.manifest().notes());
        assertTrue(s.manifest().withdraw().isEmpty(), s.manifest().toString());
        assertEquals(1, s.projected().usedSlots(), "one slot however many");
        assertEquals(300, s.projected().count(NoteForm.NOTED, IRON));
        assertEquals(0, s.projected().count(NoteForm.UNNOTED, IRON));
    }

    @Test
    void a_noted_cargo_fits_a_pack_with_one_slot_free() {
        int[][] full = new int[29][];
        for (int i = 0; i < 29; i++) {
            full[i] = plain(ItemId.SHARK.id(), 1);
        }
        LoadoutSpec cargo = new LoadoutSpec(List.of(notedBand(300, 300), new Item(ItemId.SHARK, 0, 29, 29, false)));
        Settle s = cargo.settleAt(pack(full), bank(plain(IRON, 500)), DEFS);
        assertTrue(s.satisfiable(), "29 sharks and 300 ore in 30 slots");
        assertEquals(30, s.projected().usedSlots());
    }

    @Test
    void the_item_itself_aboard_a_noted_band_is_banked_and_comes_back_out_as_notes() {
        Settle s = new LoadoutSpec(List.of(notedBand(300, 300)))
                .settleAt(pack(plain(IRON, 1), plain(IRON, 1), plain(IRON, 1)), bank(plain(IRON, 297)), DEFS);
        assertTrue(s.satisfiable(), "the three aboard make up the 300");
        assertEquals(Map.of(ItemId.IRON_ORE, 0L), s.manifest().deposit());
        assertEquals(Map.of(ItemId.IRON_ORE, 300L), s.manifest().notes());
        assertEquals(1, s.projected().usedSlots());
    }

    @Test
    void a_receiver_banks_its_delivered_notes_and_draws_a_load_in_the_same_stop() {
        LoadoutSpec work = new LoadoutSpec(List.of(plainBand(1, 14, 14)));
        Settle s = work.settleAt(pack(notes(IRON, 300)), bank(), DEFS);
        assertTrue(s.satisfiable(), s.toString());
        assertEquals(Map.of(ItemId.IRON_ORE, 14L), s.manifest().withdraw());
        assertEquals(Map.of(ItemId.IRON_ORE, 0L), s.manifest().notes());
        assertEquals(14, s.projected().count(NoteForm.UNNOTED, IRON));
        assertEquals(0, s.projected().count(NoteForm.NOTED, IRON));
        assertEquals(14, s.projected().usedSlots());
        assertTrue(work.satisfiedBy(s.projected()), "the stop leaves the pack the spec accepts");
    }

    @Test
    void a_station_holding_only_notes_is_out_of_work_and_one_stop_loads_both_inputs() {
        int copper = ItemId.COPPER_ORE.id();
        int tin = ItemId.TIN_ORE.id();
        int bar = ItemId.BRONZE_BAR.id();
        LoadoutSpec station = new LoadoutSpec(List.of(
                new Item(ItemId.COPPER_ORE, 1, 14, 14, false),
                new Item(ItemId.TIN_ORE, 1, 14, 14, false),
                new LoadoutSpec.Pool(List.of(ItemId.BRONZE_BAR), 0, 0, 2)));
        Inventory delivered = pack(notes(copper, 300), notes(tin, 300), plain(bar, 1), plain(bar, 1));
        assertEquals(300, delivered.count(NoteForm.BOTH, copper), "owned: the notes are stock");
        assertEquals(0, delivered.count(copper), "usable: none");
        assertFalse(station.minsMetBy(delivered), "no ore to work");
        assertFalse(station.satisfiedBy(delivered));

        Settle s = station.settleAt(delivered, bank(), DEFS);
        assertTrue(s.satisfiable(), s.toString());
        assertEquals(
                Map.of(ItemId.COPPER_ORE, 14L, ItemId.TIN_ORE, 14L),
                s.manifest().withdraw());
        assertEquals(
                Map.of(ItemId.COPPER_ORE, 0L, ItemId.TIN_ORE, 0L), s.manifest().notes());
        assertEquals(Map.of(ItemId.BRONZE_BAR, 0L), s.manifest().deposit(), "the product banks too");
        assertTrue(station.satisfiedBy(s.projected()), "back to work after the one stop");
        assertEquals(28, s.projected().usedSlots());
    }

    @Test
    void a_pool_banks_its_delivered_notes_and_draws_its_load_in_the_same_stop() {
        int shark = ItemId.SHARK.id();
        LoadoutSpec food = new LoadoutSpec(List.of(new LoadoutSpec.Pool(
                List.of(ItemId.SHARK, ItemId.CAKE, ItemId.PARTIAL_CAKE, ItemId.SLICE_OF_CAKE), 2, 10, 10)));
        Inventory delivered = pack(notes(shark, 300));
        assertFalse(food.minsMetBy(delivered), "300 notes are nothing to eat");

        Settle s = food.settleAt(delivered, bank(), DEFS);
        assertTrue(s.satisfiable(), s.toString());
        assertEquals(Map.of(ItemId.SHARK, 10L), s.manifest().withdraw());
        assertEquals(Map.of(ItemId.SHARK, 0L), s.manifest().notes());
        assertEquals(10, s.projected().count(NoteForm.UNNOTED, shark));
        assertTrue(food.satisfiedBy(s.projected()));

        Settle cakes = food.settleAt(pack(notes(ItemId.CAKE.id(), 5)), bank(plain(shark, 3)), DEFS);
        assertTrue(cakes.satisfiable(), cakes.toString());
        assertEquals(Map.of(ItemId.SHARK, 3L, ItemId.CAKE, 5L), cakes.manifest().withdraw());
        assertEquals(Map.of(ItemId.CAKE, 0L), cakes.manifest().notes());
    }

    @Test
    void a_pool_keeps_the_item_itself_beside_a_noted_band_of_it() {
        int shark = ItemId.SHARK.id();
        LoadoutSpec spec = new LoadoutSpec(List.of(
                new Item(ItemId.SHARK, 0, 0, Long.MAX_VALUE, false, true),
                new LoadoutSpec.Pool(List.of(ItemId.SHARK, ItemId.CAKE), 0, 5, 5)));
        Inventory held = pack(plain(shark, 1), plain(shark, 1), plain(shark, 1));
        assertTrue(spec.satisfiedBy(held));
        Settle s = spec.settleAt(held, bank(), DEFS);
        assertTrue(s.manifest().isEmpty(), s.manifest().toString());
        assertEquals(3, s.projected().count(NoteForm.UNNOTED, shark));
    }

    @Test
    void an_overfull_pack_yields_held_copies_never_a_noted_band() {
        int shark = ItemId.SHARK.id();
        LoadoutSpec spec = new LoadoutSpec(List.of(new Item(ItemId.SHARK, 0, 30, 30, false), notedBand(100, 300)));
        int[][] sharks = new int[30][];
        for (int i = 0; i < 30; i++) {
            sharks[i] = plain(shark, 1);
        }
        Settle s = spec.settleAt(pack(sharks), bank(plain(shark, 5), plain(IRON, 300)), DEFS);
        assertTrue(s.satisfiable(), s.toString());
        assertEquals(Map.of(ItemId.SHARK, 29L), s.manifest().deposit());
        assertEquals(Map.of(ItemId.IRON_ORE, 300L), s.manifest().notes());
        assertEquals(30, s.projected().usedSlots());
    }

    @Test
    void notes_no_band_names_are_surplus_and_bank() {
        LoadoutSpec coalOnly = new LoadoutSpec(List.of(new Item(ItemId.COAL, 1, 5, 5, false)));
        Inventory held = pack(notes(IRON, 300), plain(COAL, 1));
        assertFalse(coalOnly.satisfiedBy(held), "LAW 1: an unclaimed slot is a stop");
        Settle s = coalOnly.settleAt(held, bank(plain(COAL, 10)), DEFS);
        assertEquals(Map.of(ItemId.IRON_ORE, 0L), s.manifest().notes());
        assertEquals(0, s.projected().count(NoteForm.BOTH, IRON));
    }

    @Test
    void a_stop_without_notes_names_none() {
        Settle s = new LoadoutSpec(List.of(plainBand(1, 14, 14))).settleAt(pack(), bank(plain(IRON, 20)), DEFS);
        assertTrue(s.manifest().notes().isEmpty());
        assertEquals(Map.of(ItemId.IRON_ORE, 14L), s.manifest().withdraw());
        assertFalse(s.manifest().toString().contains("notes"), "the stop reads as it always did");
    }

    @Test
    void a_noted_band_is_its_own_band() {
        assertNotEquals(notedBand(1, 14), new Item(ItemId.IRON_ORE, 1, 14, Long.MAX_VALUE, false));
        assertNotEquals(
                new LoadoutSpec(List.of(notedBand(1, 14))).fingerprint(),
                new LoadoutSpec(List.of(new Item(ItemId.IRON_ORE, 1, 14, Long.MAX_VALUE, false))).fingerprint());
        assertEquals(
                2,
                new LoadoutSpec(List.of(notedBand(1, 14), plainBand(1, 14, 14)))
                        .requirements()
                        .size(),
                "the notes and the item are two stacks, never merged");
    }

    @Test
    void a_pack_states_its_notes_as_noted_bands() {
        LoadoutSpec described = LoadoutSpec.ofPack(pack(notes(IRON, 300), plain(IRON, 2)));
        assertTrue(described.satisfiedBy(pack(notes(IRON, 300), plain(IRON, 2))));
        assertEquals(2, described.requirements().size());
    }

    @Test
    void the_bank_stop_manifest_splits_the_forms() {
        Route.BankStop stop = new Route.BankStop(
                new openrsc.gamedata.geometry.Point(0, 0),
                pack(notes(IRON, 300)),
                new LoadoutSpec(List.of(plainBand(1, 14, 14)))
                        .settleAt(pack(notes(IRON, 300)), bank(), DEFS)
                        .projected());
        Manifest m = stop.manifest();
        assertEquals(Map.of(ItemId.IRON_ORE, 14L), m.withdraw());
        assertEquals(Map.of(ItemId.IRON_ORE, 0L), m.notes());
        assertTrue(m.deposit().isEmpty());
    }

    @Test
    void a_min_held_as_notes_stays_the_min() {
        LoadoutSpec load = new LoadoutSpec(List.of(new Item(ItemId.IRON_ORE, 14, 14, 28, false)));
        LoadoutSpec reachable = load.reachable(pack(notes(IRON, 300)), bank(), DEFS);
        assertEquals(14, ((Item) reachable.requirements().get(0)).min(), reachable.toString());
    }
}
