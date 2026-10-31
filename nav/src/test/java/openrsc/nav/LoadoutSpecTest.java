package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.ItemRef;
import openrsc.nav.LoadoutSpec.Item;
import openrsc.nav.LoadoutSpec.Pool;
import org.junit.jupiter.api.Test;

class LoadoutSpecTest {

    private static final int CAKE = ItemId.CAKE.id();
    private static final int TWO_THIRDS = ItemId.PARTIAL_CAKE.id();

    private static final int COINS = ItemId.COINS.id();
    private static final int BONES = ItemId.BONES.id();
    private static final int FEATHER = ItemId.FEATHER.id();

    private static final ServerData DEFS = RealDefs.serverData();

    // Rows are {id, amount}, or {id, amount, 1} for a worn item.
    private static Inventory pack(int[]... slots) {
        MutableInventory m = new MutableInventory();
        for (int i = 0; i < slots.length; i++) {
            m.setSlot(i, new InventorySlot(i, slots[i][0], slots[i][1], slots[i].length > 2 && slots[i][2] == 1));
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

    private static int[] s(int id, int amount) {
        return new int[] {id, amount};
    }

    @Test
    void bands_equal_by_item_id_so_a_step_planned_again_is_the_same_step() {
        ItemRef cake = () -> CAKE;
        ItemRef cakeAgain = () -> CAKE;
        assertNotEquals(cake, cakeAgain, "the premise: two lambdas over one id are not equal");
        LoadoutSpec lastTick = new LoadoutSpec(List.of(
                new Item(cake, 1, 2, 3, true), new Pool(List.<ItemRef>of(() -> COINS, () -> FEATHER), 0, 1, 5)));
        LoadoutSpec thisTick = new LoadoutSpec(List.of(
                new Item(ItemId.CAKE, 1, 2, 3, true),
                new Pool(List.<ItemRef>of(ItemId.COINS, () -> FEATHER), 0, 1, 5)));
        assertEquals(lastTick, thisTick);
        assertEquals(lastTick.hashCode(), thisTick.hashCode());

        assertNotEquals(
                lastTick,
                new LoadoutSpec(List.of(
                        new Item(cakeAgain, 1, 2, 4, true),
                        new Pool(List.<ItemRef>of(() -> COINS, () -> FEATHER), 0, 1, 5))),
                "another max");
        assertNotEquals(
                lastTick,
                new LoadoutSpec(List.of(
                        new Item(cakeAgain, 1, 2, 3, true),
                        new Pool(List.<ItemRef>of(() -> FEATHER, () -> COINS), 0, 1, 5))),
                "another preference");
        assertNotEquals(
                lastTick,
                new LoadoutSpec(List.of(
                        new Item(cakeAgain, 1, 2, 3, false),
                        new Pool(List.<ItemRef>of(() -> COINS, () -> FEATHER), 0, 1, 5))),
                "not worn");
    }

    @Test
    void aFullPackIsUnsatisfiedUnderBankWhenFullEvenWhenEveryBandIsHappy() {
        int[][] thirty = new int[30][];
        thirty[0] = s(COINS, 500);
        for (int i = 1; i < 30; i++) {
            thirty[i] = s(BONES, 1);
        }
        Inventory full = pack(thirty);
        List<LoadoutSpec.Requirement> bands = List.of(
                new Item(ItemId.COINS, 0, 500, Long.MAX_VALUE, false), new Pool(List.of(ItemId.BONES), 0, 0, 29));

        assertTrue(
                new LoadoutSpec(bands, false).satisfiedBy(full),
                "every band is inside its own range — without the flag this pack is settled, which is "
                        + "so a full pack would go on working with nowhere to put a drop");
        assertFalse(
                new LoadoutSpec(bands, true).satisfiedBy(full),
                "with the flag, 30/30 is the trip — no free slot means no pickup of any kind");

        assertTrue(
                new LoadoutSpec(bands, true).satisfiedBy(pack(java.util.Arrays.copyOf(thirty, 29))),
                "29/30 keeps working — banking here is the bot travelling instead of doing its job");
    }

    @Test
    void anUnreachableItemMinRelaxesToWhatOwnStockHolds() {
        LoadoutSpec spec = new LoadoutSpec(List.of(new Item(ItemId.SHARK, 5, 10, 10, false)));
        LoadoutSpec r = spec.reachable(pack(s(ItemId.SHARK.id(), 1)), bank(s(ItemId.SHARK.id(), 2)), DEFS);
        assertEquals(
                3,
                ((Item) r.requirements().get(0)).min(),
                "one carried and two banked is every shark that exists: the floor cannot be higher");
        assertEquals(10, ((Item) r.requirements().get(0)).target(), "the target is untouched");
    }

    @Test
    void aReachableMinIsLeftAloneAndTheSpecIsIdentical() {
        LoadoutSpec spec = new LoadoutSpec(List.of(new Item(ItemId.SHARK, 2, 10, 10, false)));
        assertSame(
                spec,
                spec.reachable(pack(s(ItemId.SHARK.id(), 1)), bank(s(ItemId.SHARK.id(), 9)), DEFS),
                "the bank can cover the floor — nothing to relax, and no copy to make");
    }

    @Test
    void anUnfillableMinNoLongerMakesTheSettleUnsatisfiable() {
        LoadoutSpec declared = new LoadoutSpec(List.of(new Item(ItemId.SHARK, 2, 5, 5, false)));
        Inventory empty = pack();
        Bank dry = bank();
        assertFalse(
                declared.settleAt(empty, dry, DEFS).satisfiable(),
                "as declared, a floor of two against no sharks anywhere reads as unsatisfiable");
        assertTrue(
                declared.reachable(empty, dry, DEFS).settleAt(empty, dry, DEFS).satisfiable(),
                "relaxed, it is an ordinary shortfall and the trip stays plannable");
    }

    @Test
    void aPooledMinRelaxesInSLOTSBecauseThatIsWhatAPoolBands() {
        LoadoutSpec spec = new LoadoutSpec(List.of(new Pool(List.of(ItemId.SHARK, ItemId.SWORDFISH), 4, 10, 10)));
        LoadoutSpec r = spec.reachable(pack(s(ItemId.SHARK.id(), 1)), bank(s(ItemId.SWORDFISH.id(), 2)), DEFS);
        assertEquals(
                3,
                ((Pool) r.requirements().get(0)).min(),
                "one shark plus two swordfish is three slots of fish, against a floor of four");
    }

    private static Pool cakes(int min, int target, int max) {
        return new Pool(List.of(ItemId.CAKE, ItemId.PARTIAL_CAKE, ItemId.SLICE_OF_CAKE), min, target, max);
    }

    @Test
    void partials_satisfy_the_pool() {
        LoadoutSpec spec = LoadoutSpec.of(cakes(2, 12, 12));
        assertTrue(
                spec.satisfiedBy(
                        pack(s(CAKE, 1), s(CAKE, 1), s(CAKE, 1), s(CAKE, 1), s(TWO_THIRDS, 1), s(TWO_THIRDS, 1))),
                "4 cakes + 2 partials ≥ min 2 — no forced stop");
    }

    @Test
    void below_min_forces() {
        LoadoutSpec spec = LoadoutSpec.of(cakes(2, 12, 12));
        assertFalse(spec.satisfiedBy(pack(s(CAKE, 1))), "1 cake < min 2 — Law 1 violation");
    }

    @Test
    void an_incidental_stop_upgrades_toward_twelve_fulls() {
        LoadoutSpec spec = LoadoutSpec.of(cakes(2, 12, 12));
        Inventory held = pack(s(CAKE, 1), s(CAKE, 1), s(CAKE, 1), s(CAKE, 1), s(TWO_THIRDS, 1), s(TWO_THIRDS, 1));
        Settle settle = spec.settleAt(held, bank(s(CAKE, 100)), DEFS);

        assertTrue(settle.satisfiable());
        assertEquals(
                12L,
                settle.manifest().withdraw().get(ItemId.CAKE),
                "raise to the absolute target of 12 fulls (preference filling)");
        assertEquals(
                0L,
                settle.manifest().deposit().get(ItemId.PARTIAL_CAKE),
                "displaced partials lower to 0 — walk away with 12 fulls");
        assertEquals(12, settle.projected().usedSlots());
        assertEquals(12L, settle.projected().count(CAKE));
        assertTrue(spec.satisfiedBy(settle.projected()), "the projection satisfies the spec");
    }

    @Test
    void deficit_below_min_that_the_bank_cannot_cover_is_unsatisfiable() {
        LoadoutSpec spec = LoadoutSpec.of(cakes(2, 12, 12));
        Settle settle = spec.settleAt(pack(s(CAKE, 1)), bank(), DEFS);
        assertFalse(settle.satisfiable(), "1 held, empty bank, min 2");
    }

    private static int[][] bones(int n) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = s(BONES, 1);
        }
        return out;
    }

    private static Inventory wearingKit(boolean equipmentTab) {
        MutableInventory m = new MutableInventory(equipmentTab);
        for (int i = 0; i < WORN_KIT.length; i++) {
            m.setSlot(i, new InventorySlot(i, WORN_KIT[i].id(), 1, true));
        }
        return m.snapshot();
    }

    private static final ItemId[] WORN_KIT = {
        ItemId.STEEL_LONG_SWORD,
        ItemId.STEEL_KITE_SHIELD,
        ItemId.MEDIUM_BRONZE_HELMET,
        ItemId.STEEL_PLATE_MAIL_BODY,
        ItemId.STEEL_PLATE_MAIL_LEGS
    };

    private static LoadoutSpec kitAndSharks() {
        List<LoadoutSpec.Requirement> reqs = new java.util.ArrayList<>();
        for (ItemId id : WORN_KIT) {
            reqs.add(new Item(id, 1, 1, 1, true));
        }
        reqs.add(new Item(ItemId.SHARK, 0, 30, 30, false));
        return new LoadoutSpec(reqs);
    }

    @Test
    void worn_kit_takes_pack_slots_only_without_an_equipment_tab() {
        Settle uranium = kitAndSharks().settleAt(wearingKit(false), bank(s(ItemId.SHARK.id(), 100)), DEFS);
        assertTrue(uranium.satisfiable());
        assertEquals(25L, uranium.manifest().withdraw().get(ItemId.SHARK), "30 less the five worn slots");
        assertEquals(30, uranium.projected().usedSlots());

        Settle coleslaw = kitAndSharks().settleAt(wearingKit(true), bank(s(ItemId.SHARK.id(), 100)), DEFS);
        assertTrue(coleslaw.satisfiable());
        assertEquals(30L, coleslaw.manifest().withdraw().get(ItemId.SHARK), "worn gear takes none of the 30");
        assertEquals(30, coleslaw.projected().usedSlots(), "the pack full");
        assertEquals(
                5, coleslaw.projected().slots().size() - coleslaw.projected().usedSlots(), "the kit still on");
        assertTrue(coleslaw.manifest().deposit().isEmpty(), "nothing of the kit is banked for room");
    }

    @Test
    void a_wear_leaves_room_for_what_it_displaces() {
        MutableInventory m = new MutableInventory(true);
        m.setSlot(0, new InventorySlot(0, ItemId.STEEL_LONG_SWORD.id(), 1, true));
        m.setSlot(1, new InventorySlot(1, ItemId.STEEL_KITE_SHIELD.id(), 1, true));
        LoadoutSpec spec = new LoadoutSpec(
                List.of(new Item(ItemId.RUNE_2_HANDED_SWORD, 1, 1, 1, true), new Item(ItemId.SHARK, 1, 30, 30, false)),
                false,
                true);
        Settle settle = spec.settleAt(
                m.snapshot(), bank(s(ItemId.RUNE_2_HANDED_SWORD.id(), 1), s(ItemId.SHARK.id(), 100)), DEFS);

        assertTrue(settle.satisfiable());
        assertEquals(Set.of(ItemId.RUNE_2_HANDED_SWORD), settle.manifest().equip());
        assertEquals(1L, settle.manifest().withdraw().get(ItemId.RUNE_2_HANDED_SWORD));
        assertEquals(
                28L,
                settle.manifest().withdraw().get(ItemId.SHARK),
                "28 sharks and the two-hander, then the sword and shield in its place: 30");
    }

    @Test
    void a_catch_all_keeps_worn_gear_off_the_pack_at_any_fill() {
        MutableInventory m = new MutableInventory(true);
        for (int i = 0; i < 30; i++) {
            m.setSlot(i, new InventorySlot(i, BONES, 1, false));
        }
        m.setSlot(30, new InventorySlot(30, ItemId.STEEL_KITE_SHIELD.id(), 1, true));
        Inventory full = m.snapshot();
        assertTrue(full.isFull());
        LoadoutSpec spec = new LoadoutSpec(List.of(), true, true);
        Settle settle = spec.settleAt(full, bank(s(BONES, 5)), DEFS);
        assertEquals(0L, settle.manifest().deposit().get(ItemId.BONES), "the haul is banked");
        assertFalse(
                settle.manifest().deposit().containsKey(ItemId.STEEL_KITE_SHIELD),
                "the worn shield frees no pack slot, so it stays on: " + settle.manifest());
    }

    @Test
    void a_catch_all_till_a_bank_accepts_its_surplus_and_deposits_it_at_the_stop() {
        List<LoadoutSpec.Requirement> sharks = List.of(new Item(ItemId.SHARK, 1, 20, 30, false));
        int shark = ItemId.SHARK.id();
        Inventory held = pack(s(shark, 1), s(shark, 1), s(shark, 1), s(ItemId.RUNE_KITE_SHIELD.id(), 1));
        Bank banked = bank(s(shark, 100));
        LoadoutSpec keeps = new LoadoutSpec(sharks, true, true);
        LoadoutSpec banks = new LoadoutSpec(sharks, true, true, 0, true);

        assertTrue(banks.satisfiedBy(held), "the shield alone asks for no stop");
        Settle kept = keeps.settleAt(held, banked, DEFS);
        assertFalse(
                kept.manifest().deposit().containsKey(ItemId.RUNE_KITE_SHIELD),
                "a catch-all keeps it beside the sharks");
        assertEquals(20L, kept.projected().count(shark));
        Settle stop = banks.settleAt(held, banked, DEFS);
        assertEquals(0L, stop.manifest().deposit().get(ItemId.RUNE_KITE_SHIELD), "the stop banks it: " + stop);
        assertEquals(20L, stop.projected().count(shark));
        assertNotEquals(keeps.fingerprint(), banks.fingerprint());
    }

    @Test
    void a_target_the_pack_cannot_hold_is_topped_up_as_far_as_it_fits() {
        LoadoutSpec spec =
                LoadoutSpec.of(new Item(ItemId.BONES, 0, 26, 26, false), new Item(ItemId.SHARK, 1, 8, 20, false));
        Settle settle = spec.settleAt(pack(bones(26)), bank(s(ItemId.SHARK.id(), 100)), DEFS);

        assertTrue(settle.satisfiable(), "the min of one shark fits; the target is what room allows");
        assertEquals(4L, settle.projected().count(ItemId.SHARK.id()));
        assertEquals(30, settle.projected().usedSlots());
        assertTrue(spec.satisfiedBy(settle.projected()));
    }

    @Test
    void under_bank_when_full_the_top_up_stops_one_slot_short_of_full() {
        LoadoutSpec spec = new LoadoutSpec(
                List.of(new Item(ItemId.BONES, 0, 26, 26, false), new Item(ItemId.SHARK, 1, 8, 20, false)),
                true,
                false);
        Settle settle = spec.settleAt(pack(bones(26)), bank(s(ItemId.SHARK.id(), 100)), DEFS);

        assertTrue(settle.satisfiable());
        assertEquals(3L, settle.projected().count(ItemId.SHARK.id()), "a full pack is itself the next trip");
    }

    @Test
    void once_the_withdrawals_are_at_their_floor_held_surplus_above_the_min_deposits() {
        List<ItemId> kit = List.of(
                ItemId.RUNE_PLATE_MAIL_BODY,
                ItemId.RUNE_PLATE_MAIL_LEGS,
                ItemId.LARGE_RUNE_HELMET,
                ItemId.RUNE_KITE_SHIELD,
                ItemId.DRAGON_SWORD,
                ItemId.RED_CAPE,
                ItemId.GLARIALS_AMULET,
                ItemId.ROPE,
                ItemId.SLEEPING_BAG,
                ItemId.STAFF_OF_AIR,
                ItemId.CHARGED_DRAGONSTONE_AMULET,
                ItemId.AIR_RUNE,
                ItemId.CHAOS_RUNE);
        List<LoadoutSpec.Requirement> bands = new java.util.ArrayList<>();
        List<int[]> banked = new java.util.ArrayList<>();
        for (ItemId k : kit) {
            bands.add(new Item(k, 1, 1, 1, false));
            banked.add(s(k.id(), 1));
        }
        bands.add(new Item(ItemId.SHARK, 2, 17, 17, false));
        bands.add(new Item(ItemId.WATER_SKIN_MOSTLY_FULL, 0, 1, 1, false));
        banked.add(s(ItemId.SHARK.id(), 40));
        List<int[]> held = new java.util.ArrayList<>();
        for (int i = 0; i < 19; i++) {
            held.add(s(ItemId.SHARK.id(), 1));
        }
        held.add(s(ItemId.WATER_SKIN_MOSTLY_FULL.id(), 1));
        for (ItemId ballast : List.of(
                ItemId.BUCKET,
                ItemId.SHEARS,
                ItemId.NET,
                ItemId.LOBSTER_POT,
                ItemId.BIG_NET,
                ItemId.AMULET_MOULD,
                ItemId.AN_OLD_KEY,
                ItemId.FULL_WATER_SKIN,
                ItemId.CANNON_AMMO_MOULD)) {
            held.add(s(ballast.id(), 1));
        }
        LoadoutSpec spec = new LoadoutSpec(bands, false, false);
        Settle settle = spec.settleAt(pack(held.toArray(int[][]::new)), bank(banked.toArray(int[][]::new)), DEFS);

        assertTrue(settle.satisfiable(), "three sharks back onto their stack make room for the kit");
        assertEquals(16L, settle.projected().count(ItemId.SHARK.id()));
        assertEquals(30, settle.projected().usedSlots());
    }

    @Test
    void held_surplus_never_includes_a_worn_band() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.DRAGON_SWORD, 0, 1, 1, true),
                new Item(ItemId.BONES, 29, 29, 29, false),
                new Item(ItemId.CAKE, 1, 1, 1, false));
        int[][] held = new int[30][];
        held[0] = new int[] {ItemId.DRAGON_SWORD.id(), 1, 1};
        for (int i = 1; i < 30; i++) {
            held[i] = s(BONES, 1);
        }
        Settle settle = spec.settleAt(pack(held), bank(s(ItemId.DRAGON_SWORD.id(), 1), s(CAKE, 5)), DEFS);

        assertFalse(settle.satisfiable(), "only the wielded sword could give, and it may not");
        assertEquals(1L, settle.projected().count(ItemId.DRAGON_SWORD.id()));
    }

    @Test
    void an_overflow_the_mins_cause_is_still_unsatisfiable() {
        LoadoutSpec spec =
                LoadoutSpec.of(new Item(ItemId.BONES, 26, 26, 26, false), new Item(ItemId.SHARK, 5, 8, 20, false));
        assertFalse(spec.settleAt(pack(bones(26)), bank(s(ItemId.SHARK.id(), 100)), DEFS)
                .satisfiable());
    }

    private static Pool food(int min, int target, int max) {
        return new Pool(
                List.of(ItemId.SHARK, ItemId.CAKE, ItemId.PARTIAL_CAKE, ItemId.SLICE_OF_CAKE), min, target, max);
    }

    @Test
    void a_food_pool_target_the_pack_cannot_hold_is_topped_up_as_far_as_it_fits() {
        List<LoadoutSpec.Requirement> bands = new java.util.ArrayList<>();
        List<int[]> held = new java.util.ArrayList<>();
        for (ItemId k : List.of(
                ItemId.ROPE,
                ItemId.GLARIALS_AMULET,
                ItemId.SLEEPING_BAG,
                ItemId.DRAGON_SWORD,
                ItemId.RUNE_PLATE_MAIL_BODY,
                ItemId.RUNE_PLATE_MAIL_LEGS,
                ItemId.LARGE_RUNE_HELMET,
                ItemId.CAPE_OF_LEGENDS,
                ItemId.RUNE_KITE_SHIELD,
                ItemId.STAFF_OF_AIR,
                ItemId.CHARGED_DRAGONSTONE_AMULET)) {
            bands.add(new Item(k, 1, 1, 1, false));
            held.add(s(k.id(), 1));
        }
        bands.add(food(2, 17, 17));
        bands.add(new Item(ItemId.COINS, 0, 300, 25_000, false));
        bands.add(new Item(ItemId.AIR_RUNE, 2, 1020, Long.MAX_VALUE, false));
        bands.add(new Item(ItemId.CHAOS_RUNE, 1, 510, Long.MAX_VALUE, false));
        held.add(s(COINS, 300));
        held.add(s(ItemId.AIR_RUNE.id(), 9544));
        held.add(s(ItemId.CHAOS_RUNE.id(), 4721));
        held.add(s(ItemId.SHARK.id(), 1));
        LoadoutSpec spec = new LoadoutSpec(bands, true, false);
        Settle settle = spec.settleAt(pack(held.toArray(int[][]::new)), bank(s(ItemId.SHARK.id(), 1407)), DEFS);

        assertTrue(settle.satisfiable(), "the food min fits; the target is what room allows");
        assertEquals(15L, settle.projected().count(ItemId.SHARK.id()));
        assertEquals(29, settle.projected().usedSlots(), "one short of full, under bankWhenFull");
    }

    @Test
    void a_pool_yields_its_worst_ranked_withdrawals_first() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.BONES, 0, 24, 24, false), food(2, 10, 10));
        Settle settle = spec.settleAt(pack(bones(24)), bank(s(ItemId.SHARK.id(), 4), s(CAKE, 10)), DEFS);

        assertTrue(settle.satisfiable());
        assertEquals(4L, settle.projected().count(ItemId.SHARK.id()), "every shark the bank has rides");
        assertEquals(2L, settle.projected().count(CAKE), "the cake gives way, down to the room left");
        assertEquals(30, settle.projected().usedSlots());
    }

    @Test
    void a_pools_held_surplus_deposits_worst_rank_first() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.BONES, 14, 14, 14, false), food(2, 17, 17));
        int[][] held = new int[17][];
        for (int i = 0; i < 16; i++) {
            held[i] = s(ItemId.SHARK.id(), 1);
        }
        held[16] = s(CAKE, 1);
        Settle settle = spec.settleAt(pack(held), bank(s(BONES, 14), s(CAKE, 5)), DEFS);

        assertTrue(settle.satisfiable(), "the held cake goes back onto its stack to make room for the bones");
        assertEquals(16L, settle.projected().count(ItemId.SHARK.id()));
        assertEquals(0L, settle.projected().count(CAKE));
        assertEquals(30, settle.projected().usedSlots());
    }

    @Test
    void an_overflow_a_pools_min_causes_is_still_unsatisfiable() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.BONES, 26, 26, 26, false), food(5, 8, 20));
        assertFalse(spec.settleAt(pack(bones(26)), bank(s(ItemId.SHARK.id(), 100)), DEFS)
                .satisfiable());
    }

    @Test
    void the_last_declared_band_yields_first_and_a_new_stack_yields_whole() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.BONES, 0, 29, 29, false),
                new Item(ItemId.CAKE, 0, 2, 2, false),
                new Item(ItemId.FEATHER, 0, 10, 10, false));
        Settle settle = spec.settleAt(pack(bones(29)), bank(s(CAKE, 5), s(FEATHER, 50)), DEFS);

        assertTrue(settle.satisfiable());
        assertEquals(0L, settle.projected().count(FEATHER), "declared last: its new stack is the first to go");
        assertEquals(1L, settle.projected().count(CAKE), "then one cake, which is all the room left");
        assertEquals(29L, settle.projected().count(BONES), "never out of what the pack holds");
    }

    @Test
    void a_tolerance_pool_never_withdraws_and_always_deposits() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.CAKE, 0, 2, 2, false), new Pool(List.of(ItemId.BONES, ItemId.FEATHER), 0, 0, 5));
        Inventory held = pack(s(CAKE, 1), s(BONES, 1), s(BONES, 1), s(FEATHER, 30));
        assertTrue(spec.satisfiedBy(held), "loot within max is tolerated — no forced stop");

        Settle settle = spec.settleAt(held, bank(s(BONES, 50), s(CAKE, 10)), DEFS);
        assertTrue(settle.satisfiable());
        assertFalse(
                settle.manifest().withdraw().containsKey(ItemId.BONES),
                "target 0 never withdraws — the min-0-pull bug class, dead by arithmetic");
        assertEquals(0L, settle.manifest().deposit().get(ItemId.BONES), "shed to target 0");
        assertEquals(0L, settle.manifest().deposit().get(ItemId.FEATHER), "shed to target 0");
        assertEquals(
                2L, settle.manifest().withdraw().get(ItemId.CAKE), "the Item still tops up — to its absolute target");
    }

    @Test
    void pool_overflow_violates() {
        LoadoutSpec spec = new LoadoutSpec(List.of(new Pool(List.of(ItemId.BONES), 0, 0, 2)), false, true);
        assertFalse(
                spec.satisfiedBy(pack(s(BONES, 1), s(BONES, 1), s(BONES, 1))),
                "3 bones > max 2 — overflow forces (the catch-all must NOT absorb pool members)");
    }

    @Test
    void eating_within_the_band_never_replans_but_breaching_min_does() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.CAKE, 2, 12, 12, false));
        int[][] eleven = new int[11][];
        for (int i = 0; i < 11; i++) {
            eleven[i] = s(CAKE, 1);
        }
        assertTrue(spec.satisfiedBy(pack(eleven)), "12 → 11: satisfied, no replan");
        assertFalse(spec.satisfiedBy(pack(s(CAKE, 1))), "→ 1: min breached, forces the reroute");
    }

    @Test
    void unclaimed_slots_violate_unless_a_wildcard_scopes_them() {
        LoadoutSpec bare = LoadoutSpec.of(new Item(ItemId.CAKE, 1, 1, 1, false));
        Inventory held = pack(s(CAKE, 1), s(BONES, 1));
        assertFalse(bare.satisfiedBy(held), "surplus bones: no requirement claims them");

        LoadoutSpec tolerant = new LoadoutSpec(List.of(new Item(ItemId.CAKE, 1, 1, 1, false)), false, true);
        assertTrue(tolerant.satisfiedBy(held), "the catch-all scopes the surplus");
    }

    @Test
    void worn_items_surface_only_through_the_manifest() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.LEATHER_ARMOUR, 1, 1, 1, true));
        int armour = ItemId.LEATHER_ARMOUR.id();

        assertTrue(
                spec.satisfiedBy(pack(s(armour, 1))),
                "carrying satisfies — acceptance ignores worn (the runtime equips)");

        Settle carried = spec.settleAt(pack(s(armour, 1)), bank(), DEFS);
        assertTrue(
                carried.manifest().equip().contains(ItemId.LEATHER_ARMOUR), "carried-but-unworn → equip instruction");

        Settle worn = spec.settleAt(pack(new int[] {armour, 1, 1}), bank(), DEFS);
        assertTrue(worn.manifest().equip().isEmpty(), "already worn → nothing to do");
    }

    @Test
    void a_stackable_item_occupies_one_slot_and_withdraws_toward_target() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.COINS, 100, 500, 1000, false));
        Settle settle = spec.settleAt(pack(s(COINS, 60)), bank(s(COINS, 10_000)), DEFS);
        assertTrue(settle.satisfiable());
        assertEquals(500L, settle.manifest().withdraw().get(ItemId.COINS), "the absolute target: 500 coins aboard");
        assertEquals(1, settle.projected().usedSlots(), "coins stack into one slot");
        assertEquals(500L, settle.projected().count(COINS));
    }

    @Test
    void anything_still_withdraws_a_band_folded_into_it() {
        LoadoutSpec folded = new LoadoutSpec(List.of(new Item(ItemId.KNIFE, 1, 1, 1, false)), false, true);
        Settle settle = folded.settleAt(pack(s(CAKE, 1)), bank(s(ItemId.KNIFE.id(), 5)), DEFS);
        assertTrue(settle.satisfiable(), "one free slot, one knife to fetch");
        assertEquals(1L, settle.manifest().withdraw().get(ItemId.KNIFE));
        assertEquals(
                1L,
                settle.projected().count(CAKE),
                "there was room, so the catch-all kept what was aboard — it yields slots only when a "
                        + "declared band actually needs them");
    }

    @Test
    void a_declared_band_outranks_the_anything_catch_all_in_a_full_pack() {
        LoadoutSpec folded = new LoadoutSpec(List.of(new Item(ItemId.KNIFE, 1, 1, 1, false)), false, true);
        int[][] full = new int[30][];
        for (int i = 0; i < full.length; i++) {
            full[i] = s(CAKE, 1);
        }
        Settle settle = folded.settleAt(pack(full), bank(s(ItemId.KNIFE.id(), 5)), DEFS);
        assertTrue(
                settle.satisfiable(),
                "the knife is DECLARED and the cakes are only tolerated — a band's withdrawal must never "
                        + "be blocked by surplus the spec merely permits");
        assertEquals(1L, settle.manifest().withdraw().get(ItemId.KNIFE));
        assertEquals(
                29L,
                settle.manifest().deposit().get(ItemId.CAKE),
                "exactly one slot yields. Shedding the surplus WHOLESALE here would make the manifest "
                        + "non-empty for any bot carrying anything, and Engine's bank-via guard reads that as "
                        + "'this stop is worth making' — which turned every Loadout.any() walk into a bank "
                        + "detour (SeamWindowOscillationTest). Stowing the haul belongs to the stop that is "
                        + "already happening, not to the arithmetic that decides whether to stop.");
        assertEquals(29L, settle.projected().count(CAKE));
    }

    @Test
    void a_declared_band_outranks_the_remaining_pool_in_a_full_pack() {
        int maple = ItemId.MAPLE_LOGS.id();
        int axe = ItemId.RUNE_AXE.id();
        int bag = ItemId.SLEEPING_BAG.id();
        LoadoutSpec spec = new LoadoutSpec(
                List.of(
                        new Pool(List.of(ItemId.LOGS, ItemId.MAPLE_LOGS), 0, 0, Pool.REMAINING),
                        new Item(ItemId.RUNE_AXE, 1, 1, 1, false),
                        new Item(ItemId.SLEEPING_BAG, 1, 1, 1, false),
                        new Item(ItemId.KNIFE, 1, 1, 1, false)),
                false,
                false);
        int[][] full = new int[30][];
        for (int i = 0; i < 28; i++) {
            full[i] = s(maple, 1);
        }
        full[28] = s(axe, 1);
        full[29] = s(bag, 1);

        Settle settle = spec.settleAt(pack(full), bank(s(ItemId.KNIFE.id(), 9)), DEFS);
        assertTrue(
                settle.satisfiable(),
                "the knife is a declared min and the logs a min-0 ride-along — the ride-along yields");
        assertEquals(1L, settle.manifest().withdraw().get(ItemId.KNIFE));
        assertEquals(
                27L,
                settle.manifest().deposit().get(ItemId.MAPLE_LOGS),
                "exactly one log yields — the pool keeps what fits, it does not empty itself");
        assertEquals(27L, settle.projected().count(maple));
    }

    @Test
    void the_remaining_pool_keeps_its_whole_haul_when_it_fits() {
        int maple = ItemId.MAPLE_LOGS.id();
        LoadoutSpec spec = new LoadoutSpec(
                List.of(
                        new Pool(List.of(ItemId.LOGS, ItemId.MAPLE_LOGS), 0, 0, Pool.REMAINING),
                        new Item(ItemId.KNIFE, 1, 1, 1, false)),
                false,
                false);
        int[][] held = new int[20][];
        for (int i = 0; i < held.length; i++) {
            held[i] = s(maple, 1);
        }
        Settle settle = spec.settleAt(pack(held), bank(s(ItemId.KNIFE.id(), 9)), DEFS);
        assertTrue(settle.satisfiable());
        assertEquals(1L, settle.manifest().withdraw().get(ItemId.KNIFE));
        assertEquals(
                null,
                settle.manifest().deposit().get(ItemId.MAPLE_LOGS),
                "nothing to shed: a ride-along that fits is never banked by a stop made for something else");
        assertEquals(20L, settle.projected().count(maple));
    }

    @Test
    void fingerprint_is_content_identity() {
        LoadoutSpec a =
                LoadoutSpec.of(new Item(ItemId.CAKE, 2, 12, 12, false), new Item(ItemId.COINS, 0, 50, 50, false));
        LoadoutSpec b =
                LoadoutSpec.of(new Item(() -> CAKE, 2, 12, 12, false), new Item(ItemId.COINS, 0, 50, 50, false));
        assertEquals(a.fingerprint(), b.fingerprint(), "same content via a different ItemRef spelling — identical");

        LoadoutSpec c =
                LoadoutSpec.of(new Item(ItemId.CAKE, 2, 12, 13, false), new Item(ItemId.COINS, 0, 50, 50, false));
        assertNotEquals(a.fingerprint(), c.fingerprint(), "a band edge differs");

        LoadoutSpec d =
                LoadoutSpec.of(new Item(ItemId.COINS, 0, 50, 50, false), new Item(ItemId.CAKE, 2, 12, 12, false));
        assertNotEquals(
                a.fingerprint(), d.fingerprint(), "declaration order is semantic (claim order) — different identity");
    }

    @Test
    void fingerprint_separates_anything_from_the_same_bands() {
        LoadoutSpec bands = LoadoutSpec.of(new Item(ItemId.CAKE, 2, 12, 12, false));
        LoadoutSpec tolerant = new LoadoutSpec(bands.requirements(), false, true);
        assertNotEquals(bands.fingerprint(), tolerant.fingerprint());
    }

    @Test
    void a_catch_all_tolerates_an_item_without_making_it_kit() {
        LoadoutSpec kit = new LoadoutSpec(List.of(new Item(ItemId.CAKE, 1, 10, 10, false)), false, true);
        assertTrue(kit.claims(BONES), "surplus is tolerated in transit — never a reason to walk");
        assertFalse(kit.named(BONES), "but a bone is not part of a kit that names only cake");
        assertTrue(kit.named(CAKE), "what a band DOES name is kit under both questions");
        assertTrue(kit.claims(CAKE));

        LoadoutSpec strict = LoadoutSpec.of(new Item(ItemId.CAKE, 1, 10, 10, false));
        assertFalse(strict.claims(BONES), "without the catch-all the two questions coincide");
        assertFalse(strict.named(BONES));
    }

    @Test
    void aHopsKitFloorsWhatTheRestOfTheTripNeeds() {
        Inventory aboard = pack(s(COINS, 4_500), s(FEATHER, 20));
        LoadoutSpec trip = new LoadoutSpec(List.of(new Item(ItemId.COINS, 4_160, 4_160, 6_719, false)));
        Map<Integer, Long> toll = Map.of(COINS, 10L);

        LoadoutSpec step = LoadoutSpec.ofStep(aboard, Set.of(), aboard, trip, toll, DEFS);

        assertEquals(
                new Item(ItemId.COINS, 4_170, 4_500, Long.MAX_VALUE, false),
                step.requirements().get(0),
                "the legs' floor plus the toll the crossing still takes; the pack stays the target");
        assertEquals(
                new Item(ItemId.FEATHER, 0, 20, Long.MAX_VALUE, false),
                step.requirements().get(1),
                "an item the trip does not floor is described, as under the bare pack");
        assertTrue(step.satisfiedBy(aboard), "the pack meets its own kit: a step never owes a trip");
        assertEquals(
                0,
                ((Item) LoadoutSpec.ofPack(aboard).requirements().get(0)).min(),
                "the premise: the bare pack floors nothing");
    }

    @Test
    void aHopsFloorFollowsThePackDown() {
        Inventory planned = pack(s(COINS, 4_500));
        LoadoutSpec trip = new LoadoutSpec(List.of(new Item(ItemId.COINS, 4_160, 4_160, 6_719, false)));
        Inventory paid = pack(s(COINS, 4_165));

        LoadoutSpec step = LoadoutSpec.ofStep(planned, Set.of(), paid, trip, Map.of(COINS, 10L), DEFS);

        assertEquals(4_165, ((Item) step.requirements().get(0)).min());
        assertTrue(step.satisfiedBy(paid));
    }

    @Test
    void aHopsSeatWearsTheBodyAsItStandsAndItsGate() {
        int helmet = ItemId.LARGE_RUNE_HELMET.id();
        int amulet = ItemId.GLARIALS_AMULET.id();
        int cape = ItemId.RED_CAPE.id();
        Inventory planned = pack(new int[] {helmet, 1}, new int[] {amulet, 1}, new int[] {cape, 1, 1});
        Inventory held = pack(new int[] {helmet, 1, 1}, new int[] {amulet, 1}, new int[] {cape, 1});
        LoadoutSpec trip = LoadoutSpec.empty();

        LoadoutSpec step = LoadoutSpec.ofStep(planned, Set.of(amulet), held, trip, Map.of(), DEFS);

        assertTrue(worn(step, helmet), "on the body now: the step's seat never takes it off");
        assertTrue(worn(step, amulet), "the crossing's gate: the step's seat puts it on");
        assertFalse(worn(step, cape), "the host took it off: the step's seat never puts it back");
        assertTrue(step.satisfiedBy(held), "worn never affects acceptance: the step still owes no trip");
    }

    @Test
    void theGateWinsTheSlotItDisplaces() {
        int kitAmulet = ItemId.DIAMOND_AMULET_OF_POWER.id();
        int glarial = ItemId.GLARIALS_AMULET.id();
        int helmet = ItemId.LARGE_RUNE_HELMET.id();
        Inventory body = pack(new int[] {kitAmulet, 1, 1}, new int[] {glarial, 1}, new int[] {helmet, 1, 1});
        for (ServerData defs : List.of(RealDefs.serverData(RealDefs.COLESLAW), DEFS)) {
            assertTrue(defs.displaces(glarial, kitAmulet), "the premise: one neck");

            LoadoutSpec step = LoadoutSpec.ofStep(body, Set.of(glarial), body, LoadoutSpec.empty(), Map.of(), defs);

            assertTrue(worn(step, glarial), "the gate goes on");
            assertFalse(worn(step, kitAmulet), "what it displaces is not held on against it");
            assertTrue(worn(step, helmet), "the rest of the body stays as it is");
        }
    }

    private static boolean worn(LoadoutSpec spec, int id) {
        for (LoadoutSpec.Requirement r : spec.requirements()) {
            if (r instanceof Item it && it.item().id() == id) {
                return it.worn();
            }
        }
        throw new AssertionError("the spec does not name " + id);
    }

    @Test
    void aHopWithNothingFlooredIsTheBarePack() {
        Inventory aboard = pack(s(CAKE, 3), s(BONES, 2));
        LoadoutSpec trip = new LoadoutSpec(List.of(new Pool(List.of(ItemId.CAKE, ItemId.PARTIAL_CAKE), 2, 3, 3)));

        assertEquals(LoadoutSpec.ofPack(aboard), LoadoutSpec.ofStep(aboard, Set.of(), aboard, trip, Map.of(), DEFS));
    }

    private static final int SHARK = ItemId.SHARK.id();

    private static Pool sharkOrCake(int min, int target, int max) {
        return new Pool(List.<ItemRef>of(ItemId.SHARK, ItemId.CAKE), min, target, max);
    }

    private static int[][] sharks(int n) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = s(SHARK, 1);
        }
        return out;
    }

    @Test
    void two_bands_of_one_item_are_carried_together() {
        LoadoutSpec spec =
                LoadoutSpec.of(new Item(ItemId.SHARK, 2, 2, 2, false), new Item(ItemId.SHARK, 2, 2, 2, false));
        assertTrue(spec.satisfiedBy(pack(sharks(4))), "two sharks twice is four sharks");
        assertFalse(spec.satisfiedBy(pack(sharks(3))), "short of the summed min");
        assertFalse(spec.satisfiedBy(pack(sharks(5))), "over the summed max");
        Settle settle = spec.settleAt(pack(), bank(s(SHARK, 100)), DEFS);
        assertEquals(4L, settle.manifest().withdraw().get(ItemId.SHARK));
    }

    @Test
    void a_band_beside_a_pool_over_its_item_each_takes_its_own() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.SHARK, 2, 2, 2, false), sharkOrCake(2, 2, 2));
        assertTrue(
                spec.satisfiedBy(pack(s(SHARK, 1), s(CAKE, 1), s(SHARK, 1), s(CAKE, 1))),
                "the band's sharks, the pool's cakes");
        assertTrue(spec.satisfiedBy(pack(sharks(4))));
        assertFalse(spec.satisfiedBy(pack(sharks(3))), "the pool is a food short");
        assertFalse(
                spec.satisfiedBy(pack(s(SHARK, 1), s(CAKE, 1), s(CAKE, 1), s(CAKE, 1))), "the band is a shark short");
        assertTrue(spec.minsMetBy(pack(s(CAKE, 1), s(SHARK, 1), s(CAKE, 1), s(SHARK, 1))));

        Settle settle = spec.settleAt(pack(), bank(s(SHARK, 3), s(CAKE, 10)), DEFS);
        assertTrue(settle.satisfiable());
        assertEquals(3L, settle.manifest().withdraw().get(ItemId.SHARK));
        assertEquals(1L, settle.manifest().withdraw().get(ItemId.CAKE));
        assertTrue(spec.satisfiedBy(settle.projected()));
    }

    @Test
    void what_a_band_cannot_take_past_its_ceiling_is_left_for_a_pool_naming_it() {
        int power = ItemId.DIAMOND_AMULET_OF_POWER.id();
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.DIAMOND_AMULET_OF_POWER, 1, 1, 1, true),
                new Pool(List.<ItemRef>of(ItemId.DIAMOND_AMULET_OF_POWER), 0, 0, 29));
        assertTrue(spec.satisfiedBy(pack(new int[] {power, 1, 1}, s(power, 1), s(power, 1))), "worn one, two made");
        Settle settle = spec.settleAt(pack(new int[] {power, 1, 1}, s(power, 1), s(power, 1)), bank(), DEFS);
        assertEquals(1L, settle.projected().count(power), "the reload banks the two made, keeps the worn one");
    }

    @Test
    void the_catch_all_keeps_only_what_no_band_names() {
        LoadoutSpec spec = new LoadoutSpec(List.of(new Item(ItemId.SHARK, 0, 1, 2, false)), false, true);
        assertFalse(spec.satisfiedBy(pack(sharks(3))), "a third shark is over its band, not the catch-all's");
        assertTrue(spec.satisfiedBy(pack(s(SHARK, 1), s(BONES, 1))), "the bone is the catch-all's");
        Settle settle = spec.settleAt(pack(sharks(3)), bank(), DEFS);
        assertEquals(1L, settle.projected().count(SHARK), "settled to its target");
    }

    @Test
    void tolerates_agrees_with_acceptance_on_a_stack_an_item_and_a_pool_both_name() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.COINS, 0, 10, 10, false),
                new Pool(List.<ItemRef>of(ItemId.COINS, ItemId.FEATHER), 0, 1, 1));
        assertTrue(spec.tolerates(COINS, 10, DEFS));
        assertTrue(spec.satisfiedBy(pack(s(COINS, 10))));
        assertFalse(spec.tolerates(COINS, 11, DEFS), "the pool never takes what the Item leaves of a stack");
        assertFalse(spec.satisfiedBy(pack(s(COINS, 11))));
    }

    @Test
    void an_item_beside_a_pool_takes_its_target_when_the_pool_has_other_food() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.SHARK, 0, 4, 4, false), sharkOrCake(2, 2, 2));
        Settle settle = spec.settleAt(pack(), bank(s(SHARK, 4), s(CAKE, 5)), DEFS);
        assertTrue(settle.satisfiable());
        assertEquals(4L, settle.manifest().withdraw().get(ItemId.SHARK), "the band's four sharks");
        assertEquals(2L, settle.manifest().withdraw().get(ItemId.CAKE), "the pool's floor from the cakes");
    }

    @Test
    void a_pack_is_accepted_when_any_split_between_the_bands_fits() {
        LoadoutSpec spec = LoadoutSpec.of(new Item(ItemId.SHARK, 0, 4, 4, false), sharkOrCake(2, 2, 2));
        assertTrue(
                spec.satisfiedBy(pack(s(SHARK, 1), s(SHARK, 1), s(SHARK, 1), s(SHARK, 1), s(CAKE, 1), s(CAKE, 1))),
                "four sharks to the band, the cakes to the pool");
        assertFalse(spec.satisfiedBy(
                pack(s(SHARK, 1), s(SHARK, 1), s(SHARK, 1), s(SHARK, 1), s(SHARK, 1), s(CAKE, 1), s(CAKE, 1))));
    }

    @Test
    void mins_are_met_when_any_split_between_the_pools_meets_them() {
        LoadoutSpec spec = LoadoutSpec.of(sharkOrCake(2, 2, 2), new Pool(List.<ItemRef>of(ItemId.SHARK), 2, 2, 2));
        Inventory pack = pack(s(SHARK, 1), s(SHARK, 1), s(CAKE, 1), s(CAKE, 1));
        assertTrue(spec.minsMetBy(pack), "the cakes to the first pool, the sharks to the second");
        assertTrue(spec.satisfiedBy(pack));
    }

    @Test
    void a_worn_band_trimmed_to_none_is_not_put_on() {
        int[][] bones = new int[30][];
        for (int i = 0; i < 30; i++) {
            bones[i] = s(BONES, 1);
        }
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.BONES, 0, 30, 30, false), new Item(ItemId.STEEL_KITE_SHIELD, 0, 1, 1, true));
        Settle settle = spec.settleAt(pack(bones), bank(s(ItemId.STEEL_KITE_SHIELD.id(), 1)), DEFS);
        assertTrue(
                settle.manifest().equip().isEmpty(),
                "no room for the shield, so nothing to wear: " + settle.manifest());
    }

    @Test
    void under_bank_when_full_what_no_band_names_yields_to_a_band() {
        int[][] cakes = new int[29][];
        for (int i = 0; i < 29; i++) {
            cakes[i] = s(CAKE, 1);
        }
        LoadoutSpec spec = new LoadoutSpec(List.of(new Item(ItemId.KNIFE, 0, 1, 1, false)), true, true);
        Settle settle = spec.settleAt(pack(cakes), bank(s(ItemId.KNIFE.id(), 5)), DEFS);
        assertEquals(1, settle.projected().count(ItemId.KNIFE.id()), "the knife over a cake: " + settle.manifest());
        assertTrue(spec.satisfiedBy(settle.projected()), "and one slot short of full");
    }

    @Test
    void a_withdrawal_the_trim_cut_frees_no_bank_stack() {
        int[][] bones = new int[30][];
        for (int i = 0; i < 30; i++) {
            bones[i] = s(BONES, 1);
        }
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.BONES, 0, 30, 30, false),
                new Item(ItemId.KNIFE, 1, 1, 1, false),
                new Item(ItemId.SHARK, 0, 5, 5, false));
        Bank full = Bank.of(2, List.of(new BankSlot(0, SHARK, 5), new BankSlot(1, ItemId.KNIFE.id(), 2)));
        Settle settle = spec.settleAt(pack(bones), full, DEFS);
        assertFalse(
                settle.satisfiable(),
                "no shark is drawn after all, so no stack empties for the bone the knife displaces: "
                        + settle.manifest());
    }

    @Test
    void a_wears_displaced_room_comes_before_the_catch_all() {
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        List<InventorySlot> held = List.of(
                new InventorySlot(0, ItemId.DRAGON_SWORD.id(), 1, true, false),
                new InventorySlot(1, ItemId.STEEL_KITE_SHIELD.id(), 1, true, false),
                new InventorySlot(2, CAKE, 1, false, false),
                new InventorySlot(3, CAKE, 1, false, false));
        LoadoutSpec spec = new LoadoutSpec(
                List.of(new Item(ItemId.RUNE_2_HANDED_SWORD, 1, 1, 1, true), new Item(ItemId.SHARK, 1, 27, 27, false)),
                false,
                true);
        Settle settle = spec.settleAt(
                Inventory.of(held, true), bank(s(ItemId.RUNE_2_HANDED_SWORD.id(), 1), s(SHARK, 100)), coleslaw);
        assertEquals(
                27, settle.projected().count(SHARK), "the band's sharks over the second cake: " + settle.manifest());
        assertEquals(1, settle.projected().count(CAKE));
    }

    @Test
    void under_bank_when_full_held_surplus_yields_to_leave_a_slot() {
        int[][] held = new int[30][];
        held[0] = s(ItemId.KNIFE.id(), 1);
        for (int i = 1; i < 30; i++) {
            held[i] = s(CAKE, 1);
        }
        LoadoutSpec spec = new LoadoutSpec(
                List.of(new Item(ItemId.KNIFE, 0, 1, 1, false), new Pool(List.<ItemRef>of(ItemId.CAKE), 0, 29, 29)),
                true);
        Settle settle = spec.settleAt(pack(held), bank(s(CAKE, 5)), DEFS);
        assertTrue(settle.satisfiable());
        assertEquals(29, settle.projected().usedSlots(), "a cake banked: " + settle.manifest());
        assertTrue(spec.satisfiedBy(settle.projected()));
    }

    @Test
    void reachable_lowers_the_mins_against_one_shared_stock() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.SHARK, 2, 2, 2, false), new Pool(List.<ItemRef>of(ItemId.SHARK), 2, 2, 2));
        LoadoutSpec reached = spec.reachable(pack(), bank(s(SHARK, 3)), DEFS);
        Settle settle = reached.settleAt(pack(), bank(s(SHARK, 3)), DEFS);
        assertTrue(settle.satisfiable(), "three sharks for two floors of two: the pool's lowered to one");
        assertEquals(3L, settle.manifest().withdraw().get(ItemId.SHARK));
    }

    @Test
    void a_pool_member_another_item_claims_is_not_the_pools_spare() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.SHARK, 0, 5, 5, false), new Item(ItemId.CAKE, 0, 5, 5, false), sharkOrCake(3, 3, 3));
        Settle settle = spec.settleAt(pack(), bank(s(SHARK, 5), s(CAKE, 3)), DEFS);
        assertTrue(settle.satisfiable(), "the pool keeps its three");
        assertTrue(spec.satisfiedBy(settle.projected()));
    }

    @Test
    void reachable_does_not_count_a_stack_an_items_floor_draws_from() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.COINS, 5, 5, 5, false), new Pool(List.<ItemRef>of(ItemId.COINS), 1, 1, 1));
        LoadoutSpec reached = spec.reachable(pack(), bank(s(COINS, 50)), DEFS);
        assertTrue(reached.settleAt(pack(), bank(s(COINS, 50)), DEFS).satisfiable(), "the pool's floor lowered");
    }

    @Test
    void tolerates_follows_a_pool_floor_that_takes_a_stack_the_item_leaves_untouched() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.COINS, 0, 0, 10, false),
                new Pool(List.<ItemRef>of(ItemId.COINS, ItemId.FEATHER), 1, 1, 1));
        assertTrue(spec.satisfiedBy(pack(s(COINS, 50))));
        assertTrue(spec.tolerates(COINS, 50, DEFS));
    }

    private static Item sharkFill(long min, long target, long max) {
        return new Item(ItemId.SHARK, min, target, max, false, false, true);
    }

    private static Item logBand(long n) {
        return new Item(ItemId.LOGS, n, n, n, false);
    }

    private static int[][] logSlots(int n) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = s(ItemId.LOGS.id(), 1);
        }
        return out;
    }

    @Test
    void a_fill_band_takes_the_room_the_other_bands_leave() {
        LoadoutSpec spec = LoadoutSpec.of(logBand(5), sharkFill(1, 3, 28));
        Settle settle = spec.settleAt(pack(), bank(s(SHARK, 50), s(ItemId.LOGS.id(), 5)), DEFS);
        assertTrue(settle.satisfiable(), settle.manifest().toString());
        assertEquals(25, settle.projected().count(SHARK), "30 slots less the five logs, not the nominal 3");
    }

    @Test
    void a_fill_band_keeps_what_it_holds_and_tops_up_to_the_room() {
        LoadoutSpec spec = LoadoutSpec.of(logBand(5), sharkFill(1, 3, 28));
        int[][] held = new int[13][];
        System.arraycopy(logSlots(5), 0, held, 0, 5);
        System.arraycopy(sharks(8), 0, held, 5, 8);
        Settle settle = spec.settleAt(pack(held), bank(s(SHARK, 50)), DEFS);
        assertEquals(25, settle.projected().count(SHARK));
        assertFalse(settle.manifest().deposit().containsKey(ItemId.SHARK), "the eight aboard stay");
    }

    @Test
    void a_fill_band_stops_at_its_max_and_at_the_bank() {
        assertEquals(
                10,
                LoadoutSpec.of(logBand(5), sharkFill(1, 3, 10))
                        .settleAt(pack(), bank(s(SHARK, 50), s(ItemId.LOGS.id(), 5)), DEFS)
                        .projected()
                        .count(SHARK));
        assertEquals(
                7,
                LoadoutSpec.of(logBand(5), sharkFill(1, 3, 28))
                        .settleAt(pack(), bank(s(SHARK, 7), s(ItemId.LOGS.id(), 5)), DEFS)
                        .projected()
                        .count(SHARK));
    }

    @Test
    void a_fill_band_gives_way_to_the_room_kept_and_to_bank_when_full() {
        LoadoutSpec spec = LoadoutSpec.of(logBand(5), sharkFill(1, 3, 28));
        Bank bank = bank(s(SHARK, 50), s(ItemId.LOGS.id(), 5));
        assertEquals(22, spec.settleAt(pack(), bank, DEFS, 3).projected().count(SHARK), "three slots kept");
        LoadoutSpec full = new LoadoutSpec(spec.requirements(), true);
        Settle settle = full.settleAt(pack(), bank, DEFS);
        assertEquals(24, settle.projected().count(SHARK), "a full pack is not accepted under bankWhenFull");
        assertTrue(full.satisfiedBy(settle.projected()));
    }

    @Test
    void a_fill_band_whose_min_does_not_fit_is_unsatisfiable() {
        LoadoutSpec spec = LoadoutSpec.of(logBand(28), sharkFill(3, 3, 28));
        assertFalse(spec.settleAt(pack(), bank(s(SHARK, 50), s(ItemId.LOGS.id(), 28)), DEFS)
                .satisfiable());
    }

    @Test
    void a_fill_pool_fills_by_preference_to_the_room_left() {
        LoadoutSpec spec =
                LoadoutSpec.of(logBand(5), new Pool(List.<ItemRef>of(ItemId.SHARK, ItemId.CAKE), 1, 3, 28, true));
        Settle settle = spec.settleAt(pack(), bank(s(SHARK, 10), s(CAKE, 50), s(ItemId.LOGS.id(), 5)), DEFS);
        assertEquals(10, settle.projected().count(SHARK));
        assertEquals(15, settle.projected().count(CAKE));
    }

    @Test
    void a_fill_bands_target_is_no_part_of_the_fingerprint() {
        assertEquals(
                LoadoutSpec.of(logBand(5), sharkFill(1, 3, 28)).fingerprint(),
                LoadoutSpec.of(logBand(5), sharkFill(1, 20, 28)).fingerprint());
        assertNotEquals(
                LoadoutSpec.of(logBand(5), sharkFill(1, 3, 28)).fingerprint(),
                LoadoutSpec.of(logBand(5), new Item(ItemId.SHARK, 1, 3, 28, false))
                        .fingerprint());
    }

    @Test
    void only_one_band_takes_the_room_the_rest_leave() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LoadoutSpec.of(
                        sharkFill(1, 3, 28), new Pool(List.<ItemRef>of(ItemId.CAKE), 0, 0, Pool.REMAINING)));
    }

    private static int[][] joined(int[][] a, int[][] b) {
        int[][] out = new int[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static LoadoutSpec sharksAndRoomForBones() {
        return LoadoutSpec.of(
                new Item(ItemId.SHARK, 0, 5, 5, false), new Pool(List.<ItemRef>of(ItemId.BONES), 0, 0, Pool.ROOM));
    }

    @Test
    void a_room_pool_grows_while_the_pack_has_a_slot_free() {
        LoadoutSpec spec = sharksAndRoomForBones();
        assertTrue(spec.satisfiedBy(pack(joined(sharks(5), bones(24)))), "one slot free");
        assertFalse(spec.satisfiedBy(pack(joined(sharks(5), bones(25)))), "a full pack holding bones");
        assertTrue(spec.tolerates(BONES, 25, DEFS), "no fixed ceiling on the count itself");
    }

    @Test
    void a_full_pack_is_refused_only_when_the_room_pool_holds_more_than_its_target() {
        LoadoutSpec spec = LoadoutSpec.of(
                new Item(ItemId.SHARK, 0, 30, 30, false), new Pool(List.<ItemRef>of(ItemId.BONES), 0, 0, Pool.ROOM));
        assertTrue(spec.satisfiedBy(pack(sharks(30))), "full, but of sharks the loadout wants");
    }

    @Test
    void a_stop_settles_a_room_pool_to_its_target() {
        Settle settle = sharksAndRoomForBones().settleAt(pack(joined(sharks(5), bones(25))), bank(), DEFS);
        assertTrue(settle.satisfiable(), settle.manifest().toString());
        assertEquals(0, settle.projected().count(BONES), "the bones banked down to the target");
        assertEquals(5, settle.projected().count(SHARK));
    }

    @Test
    void a_fill_band_keeps_its_own_copies_from_a_pool_that_also_names_them() {
        LoadoutSpec spec = new LoadoutSpec(
                List.of(sharkFill(1, 25, 30), new Pool(List.<ItemRef>of(ItemId.BONES, ItemId.SHARK), 0, 0, Pool.ROOM)),
                true);
        Settle settle = spec.settleAt(pack(joined(sharks(20), bones(9))), bank(s(SHARK, 5)), DEFS);
        assertTrue(settle.satisfiable(), settle.manifest().toString());
        assertEquals(25, settle.projected().count(SHARK), "the twenty aboard kept, five drawn");
        assertEquals(0, settle.projected().count(BONES), "the loot banked to its target");
    }

    @Test
    void a_fill_band_leaves_a_pool_naming_its_item_what_the_pools_min_needs() {
        LoadoutSpec spec = LoadoutSpec.of(sharkFill(3, 3, 3), new Pool(List.<ItemRef>of(ItemId.SHARK), 2, 2, 2));
        Settle settle = spec.settleAt(pack(sharks(5)), bank(), DEFS);
        assertTrue(settle.satisfiable(), settle.manifest().toString());
        assertEquals(5, settle.projected().count(SHARK));
        assertTrue(spec.satisfiedBy(settle.projected()));
    }
}
