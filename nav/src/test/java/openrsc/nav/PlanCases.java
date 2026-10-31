package openrsc.nav;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.ItemRef;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.planner.InputsProbeBot;

/**
 * Seeded plan requests for property tests, most of them about a crossing whose gate reads the pack or
 * that charges a fare: an account with a pack (notes too, on a world with them), a bank, quests and
 * levels, a loadout (now and then with food that fills the pack), and a policy, now and then one that
 * lifts a gate, re-prices a crossing or opens every gate.
 */
final class PlanCases {

    private static final ItemId[] FILLER = {
        ItemId.LOGS, ItemId.BONES, ItemId.IRON_ORE, ItemId.SHARK, ItemId.CAKE, ItemId.LOBSTER, ItemId.KNIFE
    };
    private static final ItemId[] FOOD = {ItemId.SHARK, ItemId.CAKE, ItemId.LOBSTER, ItemId.SWORDFISH};

    private final ServerData defs;
    private final CollisionMap map;
    private final List<Transport> gated = new ArrayList<>();
    private final List<Integer> items;
    private final List<Integer> noteable = new ArrayList<>();
    private final List<BankSite> banks;

    PlanCases(ServerData defs, Pathfinder pf) {
        this.defs = defs;
        this.map = defs.collisionMap();
        this.banks = pf.navInputs().banks();
        Set<Integer> palette = new LinkedHashSet<>();
        AccountFacts probe = account(new Random(0), null, List.of());
        for (Transport t : pf.transports()) {
            boolean interesting = !t.consumes(probe).isEmpty();
            for (Fact f : t.prereq().facts()) {
                switch (f.kind()) {
                    case CARRIED_AT_LEAST, WEARING, NOT_CARRYING -> {
                        interesting = true;
                        palette.add(f.itemId());
                    }
                    case CARRYING_MELEE_WEAPON, ROOM_FOR -> interesting = true;
                    case SKILL_AT_LEAST -> interesting |= f.foodLifts();
                    default -> {}
                }
            }
            for (Transport.Consumed c : t.consumes(probe)) {
                palette.add(c.item().id());
            }
            if (interesting && !t.entries().isEmpty()) {
                gated.add(t);
            }
        }
        for (ItemId id : List.of(
                ItemId.COINS,
                ItemId.LAW_RUNE,
                ItemId.AIR_RUNE,
                ItemId.FIRE_RUNE,
                ItemId.EARTH_RUNE,
                ItemId.WATER_RUNE,
                ItemId.BRONZE_LONG_SWORD)) {
            palette.add(id.id());
        }
        for (ItemId f : FOOD) {
            palette.add(f.id());
        }
        this.items = new ArrayList<>(palette);
        items.removeIf(id -> defs.item(id) == null);
        for (ItemId f : FILLER) {
            palette.add(f.id());
        }
        for (int id : palette) {
            if (defs.ridesAsNotes(id)) {
                noteable.add(id);
            }
        }
    }

    /**
     * The request of {@code seed}, or null when its ends fall on no open tile.
     *
     * @throws IllegalArgumentException when the pack it draws holds more than a pack can
     */
    @Nullable
    PlanRequest request(long seed) {
        Random rnd = new Random(seed);
        // Notes and policy variations draw from a stream of their own, so they leave the other draws alone.
        Random extra = new Random(~seed);
        Point from;
        Area to;
        int mode = rnd.nextInt(10);
        if (mode < 6 && !gated.isEmpty()) {
            Transport t = gated.get(rnd.nextInt(gated.size()));
            Point entry = t.entries().get(rnd.nextInt(t.entries().size()));
            from = near(rnd, entry, rnd.nextBoolean() ? 6 : 25);
            Point land = t.destinationFrom(entry);
            to = area(rnd, land == null ? entry : land, rnd.nextBoolean() ? 4 : 20);
        } else if (mode < 8) {
            BankSite b = banks.get(rnd.nextInt(banks.size()));
            from = near(rnd, b.tile(), 8);
            to = area(rnd, banks.get(rnd.nextInt(banks.size())).tile(), 15);
        } else {
            from = near(rnd, banks.get(rnd.nextInt(banks.size())).tile(), 40);
            Transport t = gated.get(rnd.nextInt(gated.size()));
            Point land = t.destinationFrom(t.entries().getFirst());
            to = area(rnd, land == null ? t.entries().getFirst() : land, 10);
        }
        if (from == null || to == null) {
            return null;
        }
        AccountFacts account = account(rnd, extra, items);
        LoadoutSpec loadout = loadout(rnd);
        NavPolicy policy = new NavPolicy(
                NavPolicy.DangerAvoidance.of(rnd.nextInt(4) > 0),
                rnd.nextInt(3) == 0,
                rnd.nextInt(3) == 0,
                Map.of(),
                false);
        return new PlanRequest(account, from, to, filling(extra, withNotedBand(extra, loadout)), varied(extra, policy));
    }

    /** Now and then {@code loadout} with its food band filling the room the rest leave. */
    private LoadoutSpec filling(Random extra, LoadoutSpec loadout) {
        if (extra.nextInt(3) != 0) {
            return loadout;
        }
        List<LoadoutSpec.Requirement> reqs = new ArrayList<>(loadout.requirements());
        for (LoadoutSpec.Requirement r : reqs) {
            if (r instanceof LoadoutSpec.Pool p && p.unbounded()) {
                return loadout;
            }
        }
        for (int i = 0; i < reqs.size(); i++) {
            if (reqs.get(i) instanceof LoadoutSpec.Item it
                    && !it.noted()
                    && List.of(FOOD).contains(ItemId.getById(it.item().id()))) {
                reqs.set(i, new LoadoutSpec.Item(it.item(), it.min(), it.target(), 28, false, false, true));
                return new LoadoutSpec(reqs, loadout.bankWhenFull(), loadout.anything(), loadout.reserve());
            }
            if (reqs.get(i) instanceof LoadoutSpec.Pool p) {
                reqs.set(i, new LoadoutSpec.Pool(p.items(), p.min(), p.target(), Math.max(p.target(), 28), true));
                return new LoadoutSpec(reqs, loadout.bankWhenFull(), loadout.anything(), loadout.reserve());
            }
        }
        return loadout;
    }

    private NavPolicy varied(Random extra, NavPolicy policy) {
        int mode = extra.nextInt(20);
        if (mode == 0) {
            return policy.withAllEligible();
        }
        if (mode > 4) {
            return policy;
        }
        List<String> arm = gated.get(extra.nextInt(gated.size())).policyKeys().getFirst();
        if (arm.isEmpty()) {
            return policy;
        }
        TransportRef ref = new TransportRef.Arm(arm.getFirst());
        return mode <= 2 ? policy.lift(ref) : policy.cost(ref, extra.nextInt(2000));
    }

    /** On a world with notes, now and then {@code loadout} with a band of an item's notes. */
    private LoadoutSpec withNotedBand(Random extra, LoadoutSpec loadout) {
        if (noteable.isEmpty() || extra.nextInt(6) != 0) {
            return loadout;
        }
        int id = noteable.get(extra.nextInt(noteable.size()));
        for (LoadoutSpec.Requirement r : loadout.requirements()) {
            if (r instanceof LoadoutSpec.Item it && it.item().id() == id) {
                return loadout;
            }
        }
        long target = extra.nextInt(30);
        List<LoadoutSpec.Requirement> reqs = new ArrayList<>(loadout.requirements());
        reqs.add(new LoadoutSpec.Item(
                ItemId.getById(id), extra.nextInt(3) == 0 ? target : 0, target, target, false, true));
        return new LoadoutSpec(reqs, loadout.bankWhenFull(), loadout.anything(), loadout.reserve());
    }

    private @Nullable Point near(Random rnd, Point at, int r) {
        for (int k = 0; k < 40; k++) {
            int x = at.x() + rnd.nextInt(2 * r + 1) - r;
            int y = at.y() + rnd.nextInt(2 * r + 1) - r;
            if (map.inBounds(x, y)
                    && !map.fullyBlocked(x, y)
                    && y / CollisionMap.FLOOR_HEIGHT == at.y() / CollisionMap.FLOOR_HEIGHT) {
                return Point.of(x, y);
            }
        }
        return null;
    }

    private @Nullable Area area(Random rnd, Point at, int r) {
        Point p = near(rnd, at, r);
        if (p == null) {
            return null;
        }
        int w = rnd.nextInt(3) == 0 ? rnd.nextInt(4) : 0;
        return new Rect(p.x(), p.y(), p.x() + w, p.y() + w);
    }

    private AccountFacts account(Random rnd, @Nullable Random extra, List<Integer> palette) {
        Map<Skill, Integer> skills = new EnumMap<>(Skill.class);
        if (rnd.nextBoolean()) {
            for (Skill s : Skill.values()) {
                skills.put(s, 1 + rnd.nextInt(99));
            }
        }
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        int qmode = rnd.nextInt(4);
        for (QuestId q : QuestId.values()) {
            quests.put(
                    q.id(),
                    switch (qmode) {
                        case 0, 1 -> -1;
                        case 2 -> rnd.nextInt(3) == 0 ? -1 : rnd.nextInt(3) == 0 ? rnd.nextInt(8) : 0;
                        default -> 0;
                    });
        }
        Map<Integer, Integer> carried = new LinkedHashMap<>();
        int slots = 0;
        if (!palette.isEmpty()) {
            int kinds = rnd.nextInt(10);
            for (int k = 0; k < kinds && slots < 30; k++) {
                int id = palette.get(rnd.nextInt(palette.size()));
                if (carried.containsKey(id)) {
                    continue;
                }
                boolean stack = defs.isStackable(id);
                int n = stack
                        ? 1 + rnd.nextInt(rnd.nextBoolean() ? 10 : 2000)
                        : 1 + rnd.nextInt(Math.min(4, 30 - slots));
                carried.put(id, n);
                slots += stack ? 1 : n;
            }
        }
        int fill = rnd.nextInt(4) == 0 ? 30 - slots : rnd.nextInt(Math.max(1, 31 - slots));
        for (int k = 0; k < fill && slots < 30; k++) {
            carried.merge(FILLER[rnd.nextInt(FILLER.length)].id(), 1, Integer::sum);
            slots++;
        }
        Set<Integer> equipped = new LinkedHashSet<>();
        for (int id : carried.keySet()) {
            if (defs.item(id) != null && defs.item(id).wearable() && rnd.nextInt(3) == 0) {
                equipped.add(id);
            }
        }
        Map<Integer, Integer> bank = new LinkedHashMap<>();
        for (int id : palette) {
            if (rnd.nextInt(5) < 3) {
                bank.put(
                        id, defs.isStackable(id) ? 1 + rnd.nextInt(3000) : 1 + rnd.nextInt(rnd.nextBoolean() ? 3 : 60));
            }
        }
        for (ItemId f : FILLER) {
            if (rnd.nextBoolean()) {
                bank.merge(f.id(), 1 + rnd.nextInt(100), Integer::sum);
            }
        }
        // Slots in draw order: the probe bot's own maps iterate in an order that changes from JVM to JVM.
        InputsProbeBot sheet =
                new InputsProbeBot(defs, skills, Map.of(), Set.of(), Map.of(), quests, CacheValues.EMPTY);
        List<InventorySlot> inv = new ArrayList<>();
        carried.forEach((id, amount) -> {
            boolean worn = equipped.contains(id);
            if (defs.isStackable(id)) {
                inv.add(new InventorySlot(inv.size(), id, amount, worn));
                return;
            }
            for (int k = 0; k < amount; k++) {
                inv.add(new InventorySlot(inv.size(), id, 1, worn && k == 0));
            }
        });
        if (extra != null && !noteable.isEmpty() && extra.nextInt(4) == 0 && slots < Inventory.CAPACITY) {
            int id = noteable.get(extra.nextInt(noteable.size()));
            inv.add(new InventorySlot(inv.size(), id, 1 + extra.nextInt(40), false, true));
        }
        Inventory pack = Inventory.of(inv, defs.world().rules().equipmentTab());
        if (pack.usedSlots() > Inventory.CAPACITY) {
            throw new IllegalArgumentException("the pack drawn needs " + pack.usedSlots() + " slots");
        }
        List<BankSlot> banked = new ArrayList<>();
        bank.forEach((id, amount) -> banked.add(new BankSlot(banked.size(), id, amount)));
        Point pos = banks.isEmpty() ? Point.of(0, 0) : banks.getFirst().tile();
        return new AccountFacts.Snapshot(defs, pack, Bank.of(banked), sheet.stats(), sheet.quests(), pos);
    }

    private LoadoutSpec loadout(Random rnd) {
        int mode = rnd.nextInt(5);
        if (mode == 0) {
            return LoadoutSpec.any();
        }
        List<LoadoutSpec.Requirement> reqs = new ArrayList<>();
        if (mode == 1) {
            // A pack-filling food band.
            int target = 10 + rnd.nextInt(17);
            reqs.add(new LoadoutSpec.Item(FOOD[rnd.nextInt(FOOD.length)], rnd.nextInt(target), target, target, false));
        } else if (mode == 2) {
            List<ItemRef> members = new ArrayList<>();
            for (int k = 0; k < 1 + rnd.nextInt(3); k++) {
                ItemId f = FOOD[rnd.nextInt(FOOD.length)];
                if (!members.contains(f)) {
                    members.add(f);
                }
            }
            int min = rnd.nextInt(6);
            int target = min + rnd.nextInt(20);
            reqs.add(new LoadoutSpec.Pool(
                    members, min, target, rnd.nextInt(4) == 0 ? LoadoutSpec.Pool.REMAINING : target));
        }
        int named = rnd.nextInt(4);
        for (int k = 0; k < named && !items.isEmpty(); k++) {
            int id = items.get(rnd.nextInt(items.size()));
            boolean dup = false;
            for (LoadoutSpec.Requirement r : reqs) {
                dup |= r instanceof LoadoutSpec.Item it && it.item().id() == id;
            }
            if (dup) {
                continue;
            }
            boolean stack = defs.isStackable(id);
            long min = rnd.nextInt(3) == 0 ? 0 : stack ? rnd.nextInt(50) : rnd.nextInt(2);
            long target = min + (stack ? rnd.nextInt(200) : rnd.nextInt(2));
            long max = rnd.nextInt(3) == 0 ? Long.MAX_VALUE : target + rnd.nextInt(3);
            reqs.add(new LoadoutSpec.Item(ItemId.getById(id), min, target, max, false));
        }
        return new LoadoutSpec(
                reqs, rnd.nextInt(3) == 0, rnd.nextInt(3) == 0, rnd.nextInt(6) == 0 ? 1 + rnd.nextInt(2) : 0);
    }
}
