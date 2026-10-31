package openrsc.nav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.ItemRef;
import openrsc.nav.LoadoutSpec.Item;
import openrsc.nav.LoadoutSpec.Pool;
import openrsc.nav.LoadoutSpec.Requirement;

/** Seeded stops for property tests: a pack the game could hold, a bank, a loadout, the room to keep. */
record SettleCases(LoadoutSpec spec, Inventory pack, Bank bank, int keepFree, ServerData defs) {

    private static final ItemId[] FOOD = {
        ItemId.SHARK, ItemId.CAKE, ItemId.PARTIAL_CAKE, ItemId.SLICE_OF_CAKE, ItemId.LOBSTER
    };
    private static final ItemId[] KIT = {ItemId.ROPE, ItemId.KNIFE, ItemId.HAMMER, ItemId.BONES, ItemId.LOGS};
    private static final ItemId[] STACKS = {
        ItemId.COINS, ItemId.AIR_RUNE, ItemId.FIRE_RUNE, ItemId.LAW_RUNE, ItemId.BRONZE_ARROWS, ItemId.FEATHER
    };
    private static final ItemId[] ORES = {ItemId.IRON_ORE, ItemId.COAL};
    private static final ItemId SWORD = ItemId.DRAGON_SWORD;
    private static final ItemId SHIELD = ItemId.STEEL_KITE_SHIELD;
    private static final ItemId TWO_HANDER = ItemId.RUNE_2_HANDED_SWORD;
    private static final ItemId AMULET = ItemId.CHARGED_DRAGONSTONE_AMULET;
    private static final ItemId BODY = ItemId.RUNE_PLATE_MAIL_BODY;
    private static final ItemId[] GEAR = {SWORD, SHIELD, TWO_HANDER, AMULET, BODY};

    static SettleCases generate(long seed, ServerData defs) {
        Random rnd = new Random(seed);
        return new SettleCases(
                spec(rnd, defs), pack(rnd, defs), bank(rnd), rnd.nextInt(4) == 0 ? rnd.nextInt(4) : 0, defs);
    }

    private static Inventory pack(Random rnd, ServerData defs) {
        List<InventorySlot> slots = new ArrayList<>();
        boolean tab = defs.world().rules().equipmentTab();
        List<ItemId> worn = new ArrayList<>();
        switch (rnd.nextInt(5)) {
            case 1 -> worn.add(SWORD);
            case 2 -> worn.add(SHIELD);
            case 3 -> {
                worn.add(SWORD);
                worn.add(SHIELD);
            }
            case 4 -> worn.add(TWO_HANDER);
            default -> {}
        }
        if (rnd.nextBoolean()) {
            worn.add(AMULET);
        }
        if (rnd.nextBoolean()) {
            worn.add(BODY);
        }
        for (ItemId w : worn) {
            slots.add(new InventorySlot(slots.size(), w.id(), 1, true, false));
        }
        int loose = rnd.nextInt(31 - (tab ? 0 : worn.size()));
        Map<Integer, Boolean> stacked = new LinkedHashMap<>();
        for (int k = 0; k < loose; k++) {
            int pick = rnd.nextInt(10);
            if (pick < 4) {
                slots.add(new InventorySlot(slots.size(), FOOD[rnd.nextInt(FOOD.length)].id(), 1, false, false));
            } else if (pick < 6) {
                slots.add(new InventorySlot(slots.size(), KIT[rnd.nextInt(KIT.length)].id(), 1, false, false));
            } else if (pick < 8) {
                int id = STACKS[rnd.nextInt(STACKS.length)].id();
                if (stacked.putIfAbsent(id, true) == null) {
                    slots.add(new InventorySlot(slots.size(), id, 1 + rnd.nextInt(500), false, false));
                }
            } else if (pick < 9) {
                slots.add(new InventorySlot(slots.size(), GEAR[rnd.nextInt(GEAR.length)].id(), 1, false, false));
            } else {
                ItemId ore = ORES[rnd.nextInt(ORES.length)];
                if (defs.ridesAsNotes(ore.id()) && rnd.nextBoolean() && stacked.putIfAbsent(~ore.id(), true) == null) {
                    slots.add(new InventorySlot(slots.size(), ore.id(), 1 + rnd.nextInt(200), false, true));
                } else {
                    slots.add(new InventorySlot(slots.size(), ore.id(), 1, false, false));
                }
            }
        }
        return Inventory.of(slots, tab);
    }

    private static Bank bank(Random rnd) {
        List<BankSlot> slots = new ArrayList<>();
        List<ItemId> all = new ArrayList<>();
        all.addAll(List.of(FOOD));
        all.addAll(List.of(KIT));
        all.addAll(List.of(STACKS));
        all.addAll(List.of(ORES));
        all.addAll(List.of(GEAR));
        for (ItemId id : all) {
            if (rnd.nextInt(3) > 0) {
                slots.add(new BankSlot(slots.size(), id.id(), 1 + rnd.nextInt(rnd.nextBoolean() ? 5 : 400)));
            }
        }
        int capacity = rnd.nextInt(4) == 0 ? slots.size() + rnd.nextInt(3) : Bank.CAPACITY;
        return Bank.of(capacity, slots);
    }

    private static LoadoutSpec spec(Random rnd, ServerData defs) {
        List<Requirement> reqs = new ArrayList<>();
        int bands = 1 + rnd.nextInt(6);
        boolean remaining = false;
        boolean notes = defs.world().rules().bankNotes();
        for (int b = 0; b < bands; b++) {
            if (rnd.nextInt(3) == 0) {
                List<ItemRef> members = new ArrayList<>();
                ItemId[] from = rnd.nextInt(5) == 0 ? STACKS : rnd.nextBoolean() ? FOOD : KIT;
                int size = 1 + rnd.nextInt(3);
                for (int m = 0; m < size; m++) {
                    ItemId id = from[rnd.nextInt(from.length)];
                    if (!members.contains(id)) {
                        members.add(id);
                    }
                }
                int min = rnd.nextInt(8) == 0 ? rnd.nextInt(25) : rnd.nextInt(3);
                int target = min + (rnd.nextInt(6) == 0 ? rnd.nextInt(30 - min) : rnd.nextInt(8));
                int max = !remaining && rnd.nextInt(5) == 0
                        ? Pool.REMAINING
                        : rnd.nextInt(6) == 0 ? Pool.ROOM : target + rnd.nextInt(4);
                remaining |= max == Pool.REMAINING;
                reqs.add(new Pool(members, min, target, max));
                continue;
            }
            int kind = rnd.nextInt(10);
            ItemId id = kind < 3
                    ? FOOD[rnd.nextInt(FOOD.length)]
                    : kind < 5
                            ? KIT[rnd.nextInt(KIT.length)]
                            : kind < 7
                                    ? STACKS[rnd.nextInt(STACKS.length)]
                                    : kind < 9 ? GEAR[rnd.nextInt(GEAR.length)] : ORES[rnd.nextInt(ORES.length)];
            boolean stack = defs.isStackable(id.id());
            boolean noted = notes && defs.ridesAsNotes(id.id()) && rnd.nextInt(3) == 0;
            boolean worn = !noted && List.of(GEAR).contains(id) && rnd.nextBoolean();
            long min = stack || noted ? rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(60) : rnd.nextInt(3);
            long target = min + (stack || noted ? rnd.nextInt(300) : rnd.nextInt(6));
            long max = rnd.nextInt(4) == 0
                    ? Long.MAX_VALUE
                    : target + (stack || noted ? rnd.nextInt(200) : rnd.nextInt(3));
            if (worn) {
                min = Math.min(min, 1);
                target = Math.min(Math.max(target, min), 1);
                max = Math.max(target, Math.min(max, 1));
                if (wornTwice(reqs, id)) {
                    worn = false;
                }
            }
            reqs.add(new Item(id, min, target, max, worn, noted));
        }
        if (!remaining && rnd.nextInt(4) == 0) {
            int b = rnd.nextInt(reqs.size());
            if (reqs.get(b) instanceof Item it && !it.noted()) {
                reqs.set(b, new Item(it.item(), it.min(), it.target(), it.max(), it.worn(), false, true));
            } else if (reqs.get(b) instanceof Pool p && p.max() != Pool.ROOM) {
                reqs.set(b, new Pool(p.items(), p.min(), p.target(), p.max(), true));
            }
        }
        return new LoadoutSpec(
                reqs, rnd.nextInt(10) < 3, rnd.nextInt(10) < 3, rnd.nextInt(5) == 0 ? 1 + rnd.nextInt(2) : 0);
    }

    private static boolean wornTwice(List<Requirement> reqs, ItemId id) {
        for (Requirement r : reqs) {
            if (r instanceof Item it && it.worn() && it.item().id() == id.id()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return spec + " over " + pack.slots() + " from " + bank.slots() + " (bank " + bank.capacity() + ", keepFree "
                + keepFree + ", " + defs.world().rules().equipmentTab() + " tab)";
    }
}
