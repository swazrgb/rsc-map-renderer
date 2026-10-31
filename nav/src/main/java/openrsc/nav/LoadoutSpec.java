package openrsc.nav;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.NoteForm;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.ItemRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a pack must hold to arrive, as data the planner takes apart: a list of bands, each
 * {@code min <= target <= max}. An {@link Item} bands one item in counts; a {@link Pool} bands any
 * of several items in pack slots.
 *
 * <p>A pack is accepted ({@link #satisfiedBy}) when every band holds between its min and max and
 * every slot is claimed by some band. The loadout asks for a bank stop only for a pack it does not
 * accept, and a stop ({@link #settleAt}) settles every band to its target, a fill band to the room
 * the others leave, as far as pack and bank allow: it withdraws what is short, best pool member first,
 * and deposits what is over its target or unclaimed. Whether a band is worn never decides acceptance; it only adds to the stop's
 * {@link Manifest#equip}.
 *
 * <p>Bands claim a pack in a fixed order: every Item before any Pool, each in declaration order,
 * first each up to its min and then on up to its ceiling.
 *
 * @param bankWhenFull whether a full pack is itself not accepted, so a full pack plans a stop
 * @param anything whether slots no band names are accepted too ({@link #any()})
 * @param reserve pack slots an accepted pack keeps free, for mins still to come ({@link #reachable})
 * @param banksUnnamed whether a stop deposits what {@code anything} accepted, instead of keeping what fits
 */
public record LoadoutSpec(
        List<Requirement> requirements, boolean bankWhenFull, boolean anything, int reserve, boolean banksUnnamed) {

    /** One band of a loadout. */
    public sealed interface Requirement permits Item, Pool {

        /**
         * Whether the band takes the room the other bands leave: a stop settles it to that, within its
         * min and max, and it gives way before every other band. Its target only names a size for callers
         * that want one.
         */
        boolean fill();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("loadout[");
        for (int i = 0; i < requirements.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(requirements.get(i));
        }
        sb.append(']');
        if (anything) {
            sb.append(banksUnnamed ? "+anythingTillABank" : "+anything");
        }
        if (bankWhenFull) {
            sb.append("+bankWhenFull");
        }
        if (reserve > 0) {
            sb.append("+reserve").append(reserve);
        }
        return sb.toString();
    }

    private static String label(ItemRef ref) {
        return ItemId.nameOf(ref.id());
    }

    private static String band(long min, long target, long max) {
        return num(min) + ".." + num(target) + ".." + num(max);
    }

    private static String num(long v) {
        return v == Long.MAX_VALUE || v == Pool.REMAINING ? "*" : v == Pool.ROOM ? "room" : Long.toString(v);
    }

    /**
     * A band over one item, in counts; {@link Long#MAX_VALUE} for no ceiling. Equal by the item's id,
     * not its {@link ItemRef} object.
     *
     * @param worn whether a stop puts the item on
     * @param noted whether the band is over the item's bank notes rather than the item: it counts only
     *     the noted copies, and a stop withdraws them as one noted stack in one slot. A plain band never
     *     claims a note, so notes nothing names are deposited.
     * @param fill see {@link Requirement#fill}; a stackable item's band fills to its max
     */
    public record Item(ItemRef item, long min, long target, long max, boolean worn, boolean noted, boolean fill)
            implements Requirement {

        /**
         * @throws IllegalArgumentException unless {@code 0 <= min <= target <= max}, for a worn note, or
         *     for a note that fills
         */
        public Item {
            if (min < 0 || target < min || max < target) {
                throw new IllegalArgumentException("band must satisfy 0 <= min <= target <= max");
            }
            if (noted && worn) {
                throw new IllegalArgumentException("a note cannot be worn");
            }
            if (noted && fill) {
                throw new IllegalArgumentException("notes take one slot, so they cannot fill the pack");
            }
        }

        public Item(ItemRef item, long min, long target, long max, boolean worn, boolean noted) {
            this(item, min, target, max, worn, noted, false);
        }

        public Item(ItemRef item, long min, long target, long max, boolean worn) {
            this(item, min, target, max, worn, false, false);
        }

        /** This band with {@code min}, {@code target} and {@code max} in place of its own. */
        public Item withBand(long min, long target, long max) {
            return new Item(item, min, target, max, worn, noted, fill);
        }

        boolean claims(InventorySlot slot) {
            return slot.item().id() == item.id() && slot.noted() == noted;
        }

        long held(Inventory pack) {
            return noted ? pack.count(NoteForm.NOTED, item.id()) : pack.count(NoteForm.UNNOTED, item.id());
        }

        @Override
        public String toString() {
            return label(item)
                    + ' '
                    + band(min, target, max)
                    + (worn ? " worn" : "")
                    + (noted ? " noted" : "")
                    + (fill ? " fill" : "");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Item i
                    && item.id() == i.item.id()
                    && min == i.min
                    && target == i.target
                    && max == i.max
                    && worn == i.worn
                    && noted == i.noted
                    && fill == i.fill;
        }

        @Override
        public int hashCode() {
            int h = item.id();
            h = 31 * h + Long.hashCode(min);
            h = 31 * h + Long.hashCode(target);
            h = 31 * h + Long.hashCode(max);
            return 31 * h + (worn ? 1 : 0) + (noted ? 2 : 0) + (fill ? 4 : 0);
        }
    }

    /**
     * A band over any of {@code items}, in pack slots; the list order is the preference a stop fills
     * by. A pool takes whole slots, never a note; a stackable member's stack is one slot, and
     * withdrawing it takes the whole bank stack. Equal by its items' ids in order, its band and
     * {@link #fill}.
     */
    public record Pool(List<ItemRef> items, int min, int target, int max, boolean fill) implements Requirement {

        /**
         * As {@code max}: every slot the rest of the loadout leaves. Such a pool settles last, keeping
         * what fits beside the other bands.
         */
        public static final int REMAINING = Integer.MAX_VALUE;

        /**
         * As {@code max}: as many as the pack being checked holds, while it has a slot free. A full pack
         * holding more than the target of such a pool is not accepted; a stop settles it to its target.
         */
        public static final int ROOM = Integer.MAX_VALUE - 1;

        /**
         * @throws IllegalArgumentException unless {@code 0 <= min <= target <= max}, or for a
         *     {@link #REMAINING} pool that fills
         */
        public Pool {
            items = List.copyOf(items);
            if (min < 0 || target < min || max < target) {
                throw new IllegalArgumentException("band must satisfy 0 <= min <= target <= max");
            }
            if (fill && max == REMAINING) {
                throw new IllegalArgumentException("a pool that fills needs a max");
            }
        }

        public Pool(List<ItemRef> items, int min, int target, int max) {
            this(items, min, target, max, false);
        }

        /** This band with {@code min}, {@code target} and {@code max} in place of its own. */
        public Pool withBand(int min, int target, int max) {
            return new Pool(items, min, target, max, fill);
        }

        boolean unbounded() {
            return max == REMAINING;
        }

        /** The most the pool holds of a pack that is {@code full} or not: see {@link #ROOM}. */
        long ceiling(boolean full) {
            return max == REMAINING || (max == ROOM && !full) ? Long.MAX_VALUE : max == ROOM ? target : max;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("any(");
            for (int i = 0; i < items.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(label(items.get(i)));
            }
            return sb.append(") ")
                    .append(band(min, target, max))
                    .append(fill ? " fill" : "")
                    .toString();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pool p)
                    || min != p.min
                    || target != p.target
                    || max != p.max
                    || fill != p.fill
                    || items.size() != p.items.size()) {
                return false;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id() != p.items.get(i).id()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int h = 31 * (31 * (31 * min + target) + max) + (fill ? 1 : 0);
            for (ItemRef ref : items) {
                h = 31 * h + ref.id();
            }
            return h;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LoadoutSpec.class);

    private static final boolean WARN_UNCLAIMED =
            !"false".equalsIgnoreCase(System.getProperty("nav.loadout.warnUnclaimed"));

    private static final int WARN_CAP = 512;
    private static final Set<Long> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Merges Item bands over one item in one form into one whose bounds are their sums, worn if either
     * is, filling if either does.
     *
     * @throws IllegalArgumentException for more than one band claiming the room the rest leave: a
     *     {@link Pool#REMAINING} pool or a {@link Requirement#fill} band
     */
    public LoadoutSpec {
        requirements = mergeDuplicateItems(requirements);
        int unbounded = 0;
        for (Requirement r : requirements) {
            if ((r instanceof Pool p && p.max() == Pool.REMAINING) || r.fill()) {
                unbounded++;
            }
        }
        if (unbounded > 1) {
            throw new IllegalArgumentException(
                    "at most one band takes the room the rest leave (a REMAINING pool or a fill band) — two have "
                            + "no defined split between them; give all but one an explicit max");
        }
    }

    private static List<Requirement> mergeDuplicateItems(List<Requirement> requirements) {
        LinkedHashMap<Integer, Integer> firstIndex = new LinkedHashMap<>();
        ArrayList<Requirement> out = new ArrayList<>(requirements.size());
        for (Requirement r : requirements) {
            if (r instanceof Item it) {
                int key = formKey(it.item().id(), it.noted());
                Integer at = firstIndex.get(key);
                if (at != null) {
                    Item prev = (Item) out.get(at);
                    out.set(
                            at,
                            new Item(
                                    prev.item(),
                                    plus(prev.min(), it.min()),
                                    plus(prev.target(), it.target()),
                                    plus(prev.max(), it.max()),
                                    prev.worn() || it.worn(),
                                    prev.noted(),
                                    prev.fill() || it.fill()));
                    continue;
                }
                firstIndex.put(key, out.size());
            }
            out.add(r);
        }
        return List.copyOf(out);
    }

    private static long plus(long a, long b) {
        return a == Long.MAX_VALUE || b == Long.MAX_VALUE || a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    /** Banking only when a band is violated, and keeping no slots free. */
    public LoadoutSpec(List<Requirement> requirements) {
        this(requirements, false, false);
    }

    public LoadoutSpec(List<Requirement> requirements, boolean bankWhenFull) {
        this(requirements, bankWhenFull, false);
    }

    public LoadoutSpec(List<Requirement> requirements, boolean bankWhenFull, boolean anything) {
        this(requirements, bankWhenFull, anything, 0);
    }

    public LoadoutSpec(List<Requirement> requirements, boolean bankWhenFull, boolean anything, int reserve) {
        this(requirements, bankWhenFull, anything, reserve, false);
    }

    public static LoadoutSpec of(Requirement req, Requirement... reqs) {
        List<Requirement> list = new ArrayList<>(reqs.length + 1);
        list.add(req);
        list.addAll(Arrays.asList(reqs));

        return new LoadoutSpec(list, false);
    }

    /**
     * The loadout of nothing: no band claims anything, so a pack holding anything is not accepted and
     * a stop deposits it all. Unlike {@link #any()}, which accepts every pack.
     */
    public static LoadoutSpec empty() {
        return new LoadoutSpec(List.of(), false);
    }

    /**
     * The loadout a pack describes: each item it holds, in each form, with its count as the target, no
     * floor, no ceiling, and worn where it is worn. Every pack is accepted that holds only these.
     */
    public static LoadoutSpec ofPack(Inventory pack) {
        Map<Integer, Item> items = new LinkedHashMap<>();
        for (InventorySlot s : pack.slots()) {
            int key = formKey(s.item().id(), s.noted());
            Item prev = items.get(key);
            long count = (prev == null ? 0 : prev.target()) + s.amount();
            boolean worn = (prev != null && prev.worn()) || s.equipped();
            items.put(key, new Item(s.item(), 0, count, Long.MAX_VALUE, worn, s.noted()));
        }
        return new LoadoutSpec(new ArrayList<>(items.values()), false, false);
    }

    /**
     * The loadout one hop of a trip is taken under: {@link #ofPack} of the step's {@code pack}, with
     * each item's min raised to what the rest of the trip needs of it ({@code trip}'s floor, plus the
     * {@code fares} still to pay of the plain item), capped at its target and at what {@code held}
     * holds. An item is worn when the hop's gate {@code wears} it, or when {@code held} wears it and no
     * gate item held takes its slot.
     */
    public static LoadoutSpec ofStep(
            Inventory pack,
            Set<Integer> wears,
            Inventory held,
            LoadoutSpec trip,
            Map<Integer, Long> fares,
            ServerData defs) {
        LoadoutSpec bare = ofPack(pack);
        List<Requirement> out = new ArrayList<>(bare.requirements.size());
        boolean changed = false;
        for (Requirement r : bare.requirements) {
            Item it = (Item) r;
            int id = it.item().id();
            long min = stepFloor(it, held, trip, fares);
            boolean worn = !it.noted() && stepWorn(id, wears, held, defs);
            boolean same = min == 0 && worn == it.worn();
            changed |= !same;
            out.add(same ? it : new Item(it.item(), min, it.target(), it.max(), worn, it.noted()));
        }
        return changed ? new LoadoutSpec(out, false, false) : bare;
    }

    private static long stepFloor(Item it, Inventory held, LoadoutSpec trip, Map<Integer, Long> fares) {
        int id = it.item().id();
        long need = trip.itemFloor(id, it.noted()) + (it.noted() ? 0 : fares.getOrDefault(id, 0L));
        return Math.min(Math.min(it.target(), it.held(held)), need);
    }

    private static boolean stepWorn(int id, Set<Integer> wears, Inventory held, ServerData defs) {
        if (wears.contains(id)) {
            return true;
        }
        if (!held.isEquipped(id)) {
            return false;
        }
        for (int gate : wears) {
            if (held.count(gate) > 0 && defs.displaces(gate, id)) {
                return false;
            }
        }
        return true;
    }

    private long itemFloor(int id, boolean noted) {
        long floor = 0;
        for (Requirement r : requirements) {
            if (r instanceof Item it && it.item().id() == id && it.noted() == noted) {
                floor = Math.max(floor, it.min());
            }
        }
        return floor;
    }

    /**
     * The loadout that accepts any pack: it claims whatever no band names, so it never plans a stop of
     * its own, and a stop made for another reason leaves the pack alone. Bands added to it are still
     * enforced.
     */
    public static LoadoutSpec any() {
        return new LoadoutSpec(List.of(), false, true);
    }

    /** Whether a stop keeps what no band names, as far as it fits: {@link #anything} unless {@link #banksUnnamed}. */
    public boolean keepsUnnamed() {
        return anything && !banksUnnamed;
    }

    /** Whether a band names {@code item}, or {@link #anything} takes it. */
    public boolean claims(int item) {
        return anything || named(item);
    }

    /** Whether a band names {@code item}: {@link #claims} without {@link #anything}. */
    public boolean named(int item) {
        for (Requirement r : requirements) {
            if (r instanceof Item it && it.item().id() == item) {
                return true;
            }
            if (r instanceof Pool pool) {
                for (ItemRef m : pool.items()) {
                    if (m.id() == item) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code count} of {@code item} aboard is within what this loadout accepts of that one
     * item: the summed ceilings of the bands naming it (a pool's in slots), or {@link #anything} for an
     * item none names. The per-item half of {@link #satisfiedBy}.
     */
    public boolean tolerates(int item, long count, ServerData serverData) {
        boolean stackable = serverData.isStackable(item);
        boolean itemNamed = false;
        long cap = 0;
        for (Requirement r : requirements) {
            if (r instanceof Item it && it.item().id() == item) {
                itemNamed = true;
                cap = plus(cap, it.max());
            }
        }
        long itemMins = 0;
        for (Requirement r : requirements) {
            if (r instanceof Item it && it.item().id() == item) {
                itemMins = plus(itemMins, it.min());
            }
        }
        boolean named = itemNamed;
        for (Requirement r : requirements) {
            if (r instanceof Pool pool && rankOf(pool, item) >= 0) {
                named = true;
                if (stackable) {
                    boolean poolTakesIt = !itemNamed || (itemMins == 0 && pool.min() > 0);
                    cap = poolTakesIt ? Long.MAX_VALUE : cap;
                } else {
                    cap = plus(cap, pool.ceiling(false));
                }
            }
        }
        return named ? count <= cap : anything;
    }

    /**
     * Whether {@code pack} is accepted: every band within its min and max, every slot claimed, and,
     * when asked for, the pack not full and {@link #reserve} slots free. Under {@link #anything} an
     * item a band names but no band has room for still fails.
     */
    public boolean satisfiedBy(Inventory pack) {
        if (bankWhenFull && pack.isFull()) {
            return false;
        }
        if (reserve > 0 && pack.emptySlots() < reserve) {
            return false;
        }
        List<InventorySlot> slots = pack.slots();
        boolean full = pack.isFull();
        Claim c = claim(slots, Ceiling.MAX, full);
        boolean claimed = minsMet(c);
        for (int i = 0; i < slots.size(); i++) {
            if (c.left[i] == 0) {
                continue;
            }
            if (!anything && !namesInForm(slots.get(i))) {
                warnUnclaimed(slots.get(i).item().id());
                return false;
            }
            claimed &= !namesInForm(slots.get(i));
        }
        // The claim takes slots in a fixed order and can strand one another band wanted.
        return claimed || assignable(slots, true, full);
    }

    /** Whether every band's min is met by {@code pack}: {@link #satisfiedBy} without ceilings or unclaimed slots. */
    public boolean minsMetBy(Inventory pack) {
        return minsMet(claim(pack.slots(), Ceiling.MIN, false)) || assignable(pack.slots(), false, false);
    }

    /**
     * Whether {@code slots} can be shared out, each slot whole to one band that names it in its form,
     * so every band holds at least its min. With {@code exact}, every band also holds at most its max
     * ({@link Pool#ceiling} of a pack that is {@code full} or not) and every slot a band names is taken;
     * without it, slots may be left and bands have no ceiling. A
     * stack an Item names is that Item's, as {@link #tolerates} reads it.
     */
    private boolean assignable(List<InventorySlot> slots, boolean exact, boolean full) {
        int n = slots.size();
        int[] itemOf = new int[n];
        long[] load = new long[requirements.size()];
        List<Integer> flow = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            InventorySlot sl = slots.get(i);
            itemOf[i] = -1;
            boolean pooled = false;
            for (int r = 0; r < requirements.size(); r++) {
                if (requirements.get(r) instanceof Item it && it.claims(sl)) {
                    itemOf[i] = r;
                } else if (requirements.get(r) instanceof Pool pool
                        && !sl.noted()
                        && rankOf(pool, sl.item().id()) >= 0) {
                    pooled = true;
                }
            }
            if (itemOf[i] >= 0 && (!pooled || sl.amount() > 1)) {
                load[itemOf[i]] += sl.amount();
            } else if (itemOf[i] >= 0 || pooled) {
                flow.add(i);
            }
        }
        return flows(slots, flow, itemOf, load, exact, full);
    }

    // A circulation with lower bounds over source -> slot -> band -> sink, each slot one unit: a band's
    // floor is its min less what is already fixed to it, its ceiling its max less that.
    private boolean flows(
            List<InventorySlot> slots, List<Integer> flow, int[] itemOf, long[] load, boolean exact, boolean full) {
        int bands = requirements.size();
        int u = flow.size();
        int src = 0;
        int snk = 1;
        int slot0 = 2;
        int band0 = slot0 + u;
        int ss = band0 + bands;
        int tt = ss + 1;
        int v = tt + 1;
        int inf = u + 1;
        int[][] cap = new int[v][v];
        int[] excess = new int[v];
        for (int k = 0; k < u; k++) {
            int i = flow.get(k);
            InventorySlot sl = slots.get(i);
            if (exact) {
                excess[slot0 + k]++;
                excess[src]--;
            } else {
                cap[src][slot0 + k] = 1;
            }
            for (int r = 0; r < bands; r++) {
                boolean takes = requirements.get(r) instanceof Item
                        ? itemOf[i] == r && sl.amount() == 1
                        : !sl.noted()
                                && rankOf((Pool) requirements.get(r), sl.item().id()) >= 0;
                if (takes) {
                    cap[slot0 + k][band0 + r] = 1;
                }
            }
        }
        for (int r = 0; r < bands; r++) {
            Requirement req = requirements.get(r);
            long min = req instanceof Item it ? it.min() : ((Pool) req).min();
            long max = req instanceof Item it ? it.max() : ((Pool) req).ceiling(full);
            long lo = Math.max(0, min - load[r]);
            long hi = exact ? (max == Long.MAX_VALUE ? inf : max - load[r]) : inf;
            if (hi < lo) {
                return false;
            }
            int floor = (int) Math.min(lo, inf);
            int ceil = (int) Math.min(hi, inf);
            excess[snk] += floor;
            excess[band0 + r] -= floor;
            cap[band0 + r][snk] = ceil - floor;
        }
        cap[snk][src] = inf * 4;
        int need = 0;
        for (int x = 0; x < ss; x++) {
            if (excess[x] > 0) {
                cap[ss][x] = excess[x];
                need += excess[x];
            } else if (excess[x] < 0) {
                cap[x][tt] = -excess[x];
            }
        }
        return maxFlow(cap, ss, tt) == need;
    }

    private static int maxFlow(int[][] cap, int s, int t) {
        int v = cap.length;
        int total = 0;
        int[] prev = new int[v];
        int[] queue = new int[v];
        while (true) {
            Arrays.fill(prev, -1);
            prev[s] = s;
            int head = 0;
            int tail = 0;
            queue[tail++] = s;
            while (head < tail && prev[t] < 0) {
                int x = queue[head++];
                for (int y = 0; y < v; y++) {
                    if (prev[y] < 0 && cap[x][y] > 0) {
                        prev[y] = x;
                        queue[tail++] = y;
                    }
                }
            }
            if (prev[t] < 0) {
                return total;
            }
            int push = Integer.MAX_VALUE;
            for (int y = t; y != s; y = prev[y]) {
                push = Math.min(push, cap[prev[y]][y]);
            }
            for (int y = t; y != s; y = prev[y]) {
                cap[prev[y]][y] -= push;
                cap[y][prev[y]] += push;
            }
            total += push;
        }
    }

    private enum Ceiling {
        MIN,
        MAX
    }

    private record Claim(long[] left, long[] taken) {}

    private Claim claim(List<InventorySlot> slots, Ceiling ceiling, boolean full) {
        int n = slots.size();
        long[] left = new long[n];
        for (int i = 0; i < n; i++) {
            left[i] = slots.get(i).amount();
        }
        long[] taken = new long[requirements.size()];
        int[] idx = new int[n];
        int[] rank = new int[n];
        for (Ceiling pass : ceiling == Ceiling.MIN ? TO_MIN : TO_MAX) {
            for (int r = 0; r < requirements.size(); r++) {
                if (!(requirements.get(r) instanceof Item item)) {
                    continue;
                }
                long cap = pass == Ceiling.MIN ? item.min() : item.max();
                for (int i = 0; i < n && taken[r] < cap; i++) {
                    if (left[i] > 0 && item.claims(slots.get(i))) {
                        long take = Math.min(left[i], cap - taken[r]);
                        left[i] -= take;
                        taken[r] += take;
                    }
                }
            }
            for (int r = 0; r < requirements.size(); r++) {
                if (!(requirements.get(r) instanceof Pool pool)) {
                    continue;
                }
                long cap = pass == Ceiling.MIN ? pool.min() : pool.ceiling(full);
                int count = 0;
                for (int i = 0; i < n; i++) {
                    InventorySlot sl = slots.get(i);
                    int rk = sl.noted() || left[i] != sl.amount()
                            ? -1
                            : rankOf(pool, sl.item().id());
                    if (rk >= 0) {
                        idx[count] = i;
                        rank[count] = rk;
                        count++;
                    }
                }
                sortByRankThenAmountDesc(idx, rank, count, slots);
                for (int k = 0; k < count && taken[r] < cap; k++) {
                    left[idx[k]] = 0;
                    taken[r]++;
                }
            }
        }
        return new Claim(left, taken);
    }

    private static final List<Ceiling> TO_MIN = List.of(Ceiling.MIN);
    private static final List<Ceiling> TO_MAX = List.of(Ceiling.MIN, Ceiling.MAX);

    private boolean minsMet(Claim c) {
        for (int r = 0; r < requirements.size(); r++) {
            long min = requirements.get(r) instanceof Item item ? item.min() : ((Pool) requirements.get(r)).min();
            if (c.taken[r] < min) {
                return false;
            }
        }
        return true;
    }

    private boolean namesInForm(InventorySlot slot) {
        for (Requirement r : requirements) {
            if (r instanceof Item it
                    ? it.claims(slot)
                    : !slot.noted() && rankOf((Pool) r, slot.item().id()) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code pack} may be left as it is: {@link #satisfiedBy} the {@link #reachable} loadout, so a
     * min that neither the pack nor {@code bank} can fill asks for no stop.
     */
    public boolean accepts(Inventory pack, Bank bank, ServerData serverData) {
        return reachable(pack, bank, serverData).satisfiedBy(pack);
    }

    /**
     * This loadout with every min lowered to what {@code pack} and {@code bank} hold between them, and
     * {@link #reserve} raised by the slots the shortfall would take: a min nothing can fill no longer
     * makes every stop unsatisfiable, and room is kept for it. Returns this loadout when nothing is
     * short.
     */
    public LoadoutSpec reachable(Inventory pack, Bank bank, ServerData serverData) {
        // One stock for every band, drawn in claim order: the Items' mins, then the pools from what is left.
        Int2LongMap stock = new Int2LongOpenHashMap();
        stock.defaultReturnValue(-1);
        java.util.function.IntToLongFunction left = id -> {
            long v = stock.get(id);
            if (v < 0) {
                v = pack.count(NoteForm.BOTH, id) + bank.count(id);
                stock.put(id, v);
            }
            return v;
        };
        Requirement[] lowered = new Requirement[requirements.size()];
        IntSet touched = new IntOpenHashSet();
        boolean changed = false;
        int reserved = reserve;
        for (int pass = 0; pass < 2; pass++) {
            for (int idx = 0; idx < requirements.size(); idx++) {
                Requirement r = requirements.get(idx);
                if (pass == 0 && r instanceof Item item && item.min() > 0) {
                    int id = item.item().id();
                    long own = left.applyAsLong(id);
                    stock.put(id, Math.max(0, own - item.min()));
                    if (own > 0) {
                        touched.add(id);
                    }
                    if (own < item.min()) {
                        lowered[idx] = item.withBand(own, item.target(), item.max());
                        reserved += item.noted() || serverData.isStackable(id)
                                ? (own > 0 ? 0 : 1)
                                : (int) (item.min() - own);
                        changed = true;
                    }
                } else if (pass == 1 && r instanceof Pool pool && pool.min() > 0) {
                    long slots = 0;
                    for (ItemRef m : pool.items()) {
                        if (slots >= pool.min()) {
                            break;
                        }
                        long own = left.applyAsLong(m.id());
                        if (own <= 0) {
                            continue;
                        }
                        if (serverData.isStackable(m.id())) {
                            if (touched.contains(m.id())) {
                                continue;
                            }
                            slots++;
                            stock.put(m.id(), 0L);
                        } else {
                            long take = Math.min(own, pool.min() - slots);
                            slots += take;
                            stock.put(m.id(), own - take);
                        }
                    }
                    if (slots < pool.min()) {
                        lowered[idx] = pool.withBand((int) slots, pool.target(), pool.max());
                        reserved += (int) (pool.min() - slots);
                        changed = true;
                    }
                }
            }
        }
        if (!changed) {
            return this;
        }
        List<Requirement> out = new ArrayList<>(requirements.size());
        for (int idx = 0; idx < requirements.size(); idx++) {
            out.add(lowered[idx] != null ? lowered[idx] : requirements.get(idx));
        }
        return new LoadoutSpec(out, bankWhenFull, anything, reserved, banksUnnamed);
    }

    /**
     * Works out a bank stop from {@code pack} against {@code bank}: every band settled to its target as
     * far as stock and the pack's room allow, a {@link Requirement#fill} band to the room the others
     * leave. When the targets do not fit, withdrawals and then held items give way, a fill band first,
     * then the last declared, never below a min; mins that cannot be met or cannot fit make it
     * unsatisfiable.
     * Slots counted are the pack's own, so worn items off the pack cost none.
     */
    public Settle settleAt(Inventory pack, Bank bank, ServerData serverData) {
        return settleAt(pack, bank, serverData, 0);
    }

    /**
     * As {@link #settleAt(Inventory, Bank, ServerData)}, leaving at least {@code keepFree} pack slots
     * empty: targets give way for them as for the pack's own size, never below a min.
     */
    public Settle settleAt(Inventory pack, Bank bank, ServerData serverData, int keepFree) {
        Settle settle = settle(pack, bank, serverData, keepFree);
        if (CHECKED) {
            String broken = settleViolation(pack, bank, keepFree, settle, serverData);
            if (broken != null) {
                throw new IllegalStateException(
                        "settled a stop that breaks its contract: " + broken + " — " + this + " over " + pack.slots());
            }
        }
        return settle;
    }

    /** {@link #settleAt} without its contract check. */
    Settle settle(Inventory pack, Bank bank, ServerData serverData, int keepFree) {
        Stop stop = new Stop(pack, bank, serverData, keepFree);
        // Items settle first, then bounded pools, so a pool naming an Item's item fills from what the
        // Item leaves. A fill band holds its own copies with the Items and tops up last, to the room the
        // rest leave.
        for (int idx = 0; idx < requirements.size(); idx++) {
            if (requirements.get(idx) instanceof Item item) {
                if (item.fill()) {
                    stop.holdFill(item);
                } else {
                    stop.settleItem(idx, item);
                }
            }
        }
        for (int idx = 0; idx < requirements.size(); idx++) {
            if (requirements.get(idx) instanceof Pool pool && !pool.unbounded() && !pool.fill()) {
                stop.settlePool(idx, pool);
            }
        }
        for (int idx = 0; idx < requirements.size(); idx++) {
            Requirement r = requirements.get(idx);
            if (r instanceof Pool pool && (pool.unbounded() || pool.fill())) {
                stop.settlePool(idx, pool);
            } else if (r instanceof Item item && item.fill()) {
                stop.settleItem(idx, item);
            }
        }
        stop.takeWearRoom();
        stop.keepUnnamed();
        stop.trimWithdrawals();
        stop.yieldHeld();
        return build(pack, stop.slots, stop.finals, stop.equip, serverData, stop.satisfiable && stop.fits());
    }

    /** What a stop did to one band, in the band's units; a pool's withdrawals are rows of the stop's. */
    private static final class Band {
        long drawn;
        long fin;
        int drawSlots;
        int fill;
        int rowsFrom;
        int rowsTo;
        long trimmed;
    }

    /** One stop being worked out: what each slot and band keeps and draws, and the pack slots planned. */
    private final class Stop {

        final List<InventorySlot> slots;
        final Finals finals;
        final Set<ItemId> equip = new LinkedHashSet<>();
        boolean satisfiable = true;

        private final Inventory pack;
        private final Bank bank;
        private final ServerData defs;
        private final int n;
        // The reserve is wanted on arrival and the kept room on the way, so the same slots serve both.
        private final int capacity;
        // Under bankWhenFull a full pack is not accepted, so with no slot kept free the bands and the
        // catch-all stop one short of full.
        private final int room;
        private final boolean[] claimed;
        private final boolean[] keep;
        private final int[] keptByPool;
        private final Band[] bands;
        private final Withdrawals wd;
        private final @Nullable Int2LongMap converted;
        private final boolean[] convertedSlot;
        private final int[] scratch;
        private final int[] rank;
        // The order bands give way in: a fill band first, then the last declared.
        private final int[] giveWay;
        // The fill Item's own copies, held for it from the Items pass on.
        private final boolean[] fillHeld;
        private int planned;

        Stop(Inventory pack, Bank bank, ServerData defs, int keepFree) {
            this.pack = pack;
            this.bank = bank;
            this.defs = defs;
            this.slots = pack.slots();
            this.n = slots.size();
            this.capacity = pack.capacity() - Math.max(reserve, keepFree);
            this.room = bankWhenFull && Math.max(reserve, keepFree) == 0 ? capacity - 1 : capacity;
            this.claimed = new boolean[n];
            this.keep = new boolean[n];
            this.keptByPool = new int[n];
            this.bands = new Band[requirements.size()];
            for (int i = 0; i < bands.length; i++) {
                bands[i] = new Band();
            }
            this.wd = new Withdrawals(requirements.size() + 4);
            this.finals = new Finals(n + requirements.size() + 4);
            this.scratch = new int[n];
            this.rank = new int[n];
            this.convertedSlot = new boolean[n];
            this.converted = convertedForms(slots, claimed, convertedSlot);
            this.fillHeld = new boolean[n];
            this.giveWay = new int[requirements.size()];
            int k = 0;
            for (int idx = 0; idx < requirements.size(); idx++) {
                if (requirements.get(idx).fill()) {
                    giveWay[k++] = idx;
                }
            }
            for (int idx = requirements.size() - 1; idx >= 0; idx--) {
                if (!requirements.get(idx).fill()) {
                    giveWay[k++] = idx;
                }
            }
        }

        /** What a fill band settles to: the room the bands before it leave, within its min and max. */
        long fillTo(long min, long max) {
            return Math.max(min, Math.min(max, room - planned));
        }

        boolean fits() {
            return planned <= room;
        }

        /**
         * Holds a fill Item's copies for it before the pools settle, bar what a pool naming the item, or a
         * band over it in the other form, needs of them for its min.
         */
        void holdFill(Item item) {
            int id = item.item().id();
            boolean stacks = item.noted() || defs.isStackable(id);
            long have = 0;
            int mineCount = 0;
            for (int i = 0; i < n; i++) {
                if (!claimed[i] && item.claims(slots.get(i))) {
                    scratch[mineCount++] = i;
                    have += slots.get(i).amount();
                }
            }
            sortByAmountDesc(scratch, mineCount, slots);
            long hold = have - ((stacks ? 0 : poolFloors(id, pack, bank, defs)) + otherFormMins(id, item.noted()));
            long held = 0;
            for (int k = 0; k < mineCount && held < hold; k++) {
                int i = scratch[k];
                long amt = slots.get(i).amount();
                if (held + amt <= hold || stacks) {
                    // Kept as far as a pool can tell, so none draws a stack onto it; settleItem decides.
                    claimed[i] = true;
                    keep[i] = true;
                    fillHeld[i] = true;
                    held += amt;
                }
            }
        }

        void settleItem(int idx, Item item) {
            Band band = bands[idx];
            int id = item.item().id();
            int key = formKey(id, item.noted());
            boolean stacks = item.noted() || defs.isStackable(id);
            long have = 0;
            int mineCount = 0;
            for (int i = 0; i < n; i++) {
                if ((!claimed[i] || fillHeld[i]) && item.claims(slots.get(i))) {
                    keep[i] = false;
                    scratch[mineCount++] = i;
                    have += slots.get(i).amount();
                }
            }
            sortByAmountDesc(scratch, mineCount, slots);

            long banked = bank.count(id) - wd.drawn(id) + (converted == null ? 0 : converted.get(key));
            long supply = have + banked;
            // What a pool naming this item, or a band over it in the other form, still needs for its min is
            // left to it; a fill band left it when it held its copies.
            long forOthers =
                    item.fill() ? 0 : (stacks ? 0 : poolFloors(id, pack, bank, defs)) + otherFormMins(id, item.noted());
            long target = !item.fill() ? item.target() : stacks ? item.max() : fillTo(item.min(), item.max());
            long fin = Math.min(target, Math.max(Math.min(item.min(), supply), supply - forOthers));
            if (fin < item.min()) {
                satisfiable = false;
                fin = Math.min(item.max(), have + banked);
            }

            long kept = 0;
            int keptSlots = 0;
            for (int k = 0; k < mineCount && kept < fin; k++) {
                int i = scratch[k];
                long amt = slots.get(i).amount();
                if (kept + amt <= fin) {
                    keep[i] = true;
                    claimed[i] = true;
                    kept += amt;
                    keptSlots += pack.takesPackSlot(slots.get(i)) ? 1 : 0;
                } else if (stacks) {
                    keep[i] = true;
                    claimed[i] = true;
                    kept = fin;
                    keptSlots += pack.takesPackSlot(slots.get(i)) ? 1 : 0;
                }
            }
            // Added to what a pool drew of the item, which only a fill band settles after.
            finals.add(key, fin);
            long withdrawn = fin - kept;
            int slotsTaken = keptSlots;
            if (withdrawn > 0) {
                int cost = stacks ? (keptSlots > 0 ? 0 : 1) : (int) withdrawn;
                wd.add(key, withdrawn);
                slotsTaken += cost;
                band.drawn = withdrawn;
                band.drawSlots = cost;
            }
            band.fin = fin;
            planned += slotsTaken;

            if (item.worn() && fin >= 1) {
                boolean alreadyWorn = false;
                for (int k = 0; k < mineCount; k++) {
                    if (slots.get(scratch[k]).equipped()) {
                        alreadyWorn = true;
                        break;
                    }
                }
                if (!alreadyWorn) {
                    equip.add(ItemId.getById(id));
                }
            }
        }

        void settlePool(int idx, Pool pool) {
            Band band = bands[idx];
            int candCount = 0;
            for (int i = 0; i < n; i++) {
                int rk = claimed[i] || slots.get(i).noted()
                        ? -1
                        : rankOf(pool, slots.get(i).item().id());
                if (rk >= 0) {
                    claimed[i] = true;
                    scratch[candCount] = i;
                    rank[candCount] = rk;
                    candCount++;
                }
            }
            sortByRankThenAmountDesc(scratch, rank, candCount, slots);

            int target = pool.fill()
                    ? (int) fillTo(pool.min(), pool.max())
                    : pool.unbounded() ? Math.min(candCount, Math.max(0, room - planned)) : pool.target();
            int fill = 0;
            int offPack = 0;
            int cursor = 0;
            band.rowsFrom = wd.size();
            for (int rk = 0; rk < pool.items().size() && fill < target; rk++) {
                int id = pool.items().get(rk).id();
                while (cursor < candCount && rank[cursor] == rk && fill < target) {
                    int sl = scratch[cursor];
                    keep[sl] = true;
                    keptByPool[sl] = idx + 1;
                    finals.add(slots.get(sl).item().id(), slots.get(sl).amount());
                    fill++;
                    offPack += pack.takesPackSlot(slots.get(sl)) ? 0 : 1;
                    cursor++;
                }
                long avail = bank.count(id) - wd.drawn(id) + (converted == null ? 0 : converted.get(id));
                if (avail <= 0 || fill >= target) {
                    continue;
                }
                if (defs.isStackable(id)) {
                    if (!wd.holds(id) && !keepsAny(keep, slots, id)) {
                        wd.add(id, avail);
                        finals.add(id, avail);
                        fill++;
                    }
                } else {
                    long take = Math.min(avail, target - fill);
                    wd.add(id, take);
                    finals.add(id, take);
                    fill += (int) take;
                }
            }
            if (fill < pool.min()) {
                satisfiable = false;
            }
            band.rowsTo = wd.size();
            band.fill = fill;
            planned += fill - offPack;
        }

        // With an equipment tab, a wear moves what it displaces into the pack, and the server refuses it
        // without room for them, so their room is taken before the catch-all's. Worn gear off the pack is
        // kept by its band, or by the catch-all, which keeps every unnamed piece:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L393
        void takeWearRoom() {
            for (ItemId put : equip) {
                int displaced = 0;
                for (int i = 0; i < n; i++) {
                    InventorySlot sl = slots.get(i);
                    boolean stays = keep[i] || (keepsUnnamed() && !claimed[i] && !namesInForm(sl));
                    if (stays
                            && !pack.takesPackSlot(sl)
                            && defs.displaces(put.id(), sl.item().id())) {
                        displaced++;
                    }
                }
                planned += Math.max(0, displaced - 1);
            }
        }

        // The catch-all keeps only what fits beside the bands, and nothing when a full pack under
        // bankWhenFull is why the stop is made; what it does not keep is deposited.
        void keepUnnamed() {
            if (!keepsUnnamed()) {
                return;
            }
            boolean freeingSlots = bankWhenFull && pack.isFull();
            for (int i = 0; i < n; i++) {
                InventorySlot sl = slots.get(i);
                boolean costs = pack.takesPackSlot(sl);
                if (!convertedSlot[i] || !sl.noted() || (costs && (freeingSlots || planned >= room))) {
                    continue;
                }
                // Only the notes a band draws, past the bank's own stock, come back out as the item; the
                // catch-all keeps the rest as notes.
                int id = sl.item().id();
                long used = Math.min(sl.amount(), Math.max(0, wd.drawn(id) - bank.count(id)));
                if (used < sl.amount()) {
                    finals.add(formKey(id, true), sl.amount() - used);
                    planned += costs ? 1 : 0;
                }
            }
            for (int i = 0; i < n; i++) {
                if (!claimed[i]) {
                    claimed[i] = true;
                    boolean costs = pack.takesPackSlot(slots.get(i));
                    if (namesInForm(slots.get(i)) || (costs && (freeingSlots || planned >= room))) {
                        continue;
                    }
                    keep[i] = true;
                    finals.add(
                            formKey(slots.get(i).item().id(), slots.get(i).noted()),
                            slots.get(i).amount());
                    planned += costs ? 1 : 0;
                }
            }
        }

        // Over the pack: withdrawals give way, a fill band first, then the last declared, never below a min.
        void trimWithdrawals() {
            for (int k = 0; k < giveWay.length && planned > room; k++) {
                int idx = giveWay[k];
                Band band = bands[idx];
                if (requirements.get(idx) instanceof Pool pool) {
                    for (int row = band.rowsTo - 1; row >= band.rowsFrom && planned > room; row--) {
                        long give = Math.min(band.fill - band.trimmed - pool.min(), planned - room);
                        if (give <= 0) {
                            break;
                        }
                        int id = wd.id(row);
                        long cut = defs.isStackable(id) ? wd.amount(row) : Math.min(give, wd.amount(row));
                        int freed = defs.isStackable(id) ? 1 : (int) cut;
                        finals.add(id, -cut);
                        band.trimmed += freed;
                        planned -= freed;
                    }
                    continue;
                }
                if (!(requirements.get(idx) instanceof Item item) || band.drawn <= 0) {
                    continue;
                }
                int id = item.item().id();
                int key = formKey(id, item.noted());
                long floor = Math.max(item.min(), band.fin - band.drawn);
                if (item.noted() || defs.isStackable(id)) {
                    if (band.drawSlots == 1 && floor == 0) {
                        finals.add(key, -band.drawn);
                        band.trimmed = band.drawn;
                        planned -= 1;
                    }
                    continue;
                }
                long cut = Math.min(band.fin - floor, planned - room);
                if (cut > 0) {
                    finals.add(id, -cut);
                    band.trimmed = cut;
                    planned -= (int) cut;
                }
            }
        }

        // Still over the room: held items above a min give way too, onto stacks the bank holds before
        // new ones. Never part of a stack, which frees no slot, nor a worn band, since depositing an
        // item takes off a worn copy:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L314
        void yieldHeld() {
            // The bank's room for new stacks: its free slots, plus one per withdrawal that empties a
            // stack, less one per item deposited that it holds none of:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L200
            IntSet opened = new IntOpenHashSet();
            int newStacks = bank.emptySlots();
            IntSet emptied = new IntOpenHashSet();
            for (int row = 0; row < wd.size(); row++) {
                int key = wd.id(row);
                int id = key < 0 ? ~key : key;
                long has = bank.count(id);
                long drawnNow = finals.of(id) + finals.of(~id) - pack.count(NoteForm.BOTH, id);
                if (has > 0 && drawnNow >= has && emptied.add(id)) {
                    newStacks++;
                }
            }
            Int2LongMap held = new Int2LongOpenHashMap();
            for (InventorySlot sl : slots) {
                held.mergeLong(formKey(sl.item().id(), sl.noted()), sl.amount(), Long::sum);
            }
            for (Int2LongMap.Entry e : held.int2LongEntrySet()) {
                int key = e.getIntKey();
                int id = key < 0 ? ~key : key;
                if (e.getLongValue() > finals.of(key) && bank.count(id) <= 0 && opened.add(id)) {
                    newStacks--;
                }
            }
            boolean[] yielded = new boolean[n];
            for (int pass = 0; pass < 2 && planned > room; pass++) {
                boolean newOk = pass == 1;
                for (int k = 0; k < giveWay.length && planned > room; k++) {
                    int idx = giveWay[k];
                    Band band = bands[idx];
                    if (requirements.get(idx) instanceof Pool pool) {
                        for (int rk = pool.items().size() - 1; rk >= 0 && planned > room; rk--) {
                            int id = pool.items().get(rk).id();
                            if (defs.isStackable(id)
                                    || !(bank.count(id) > 0 || (newOk && (opened.contains(id) || newStacks > 0)))) {
                                continue;
                            }
                            for (int i = 0; i < n && planned > room && band.fill - band.trimmed > pool.min(); i++) {
                                InventorySlot sl = slots.get(i);
                                if (keptByPool[i] == idx + 1
                                        && !yielded[i]
                                        && sl.item().id() == id
                                        && !sl.equipped()
                                        && pack.takesPackSlot(sl)) {
                                    if (bank.count(id) <= 0 && opened.add(id)) {
                                        newStacks--;
                                    }
                                    yielded[i] = true;
                                    finals.add(id, -sl.amount());
                                    band.trimmed++;
                                    planned--;
                                }
                            }
                        }
                        continue;
                    }
                    if (!(requirements.get(idx) instanceof Item item) || item.worn() || item.noted()) {
                        continue;
                    }
                    int id = item.item().id();
                    if (defs.isStackable(id)
                            || !(bank.count(id) > 0 || (newOk && (opened.contains(id) || newStacks > 0)))) {
                        continue;
                    }
                    long cut = Math.min(band.fin - band.trimmed - item.min(), planned - room);
                    if (cut > 0) {
                        if (bank.count(id) <= 0 && opened.add(id)) {
                            newStacks--;
                        }
                        finals.add(id, -cut);
                        band.trimmed += cut;
                        planned -= (int) cut;
                    }
                }
            }
        }
    }

    /** Whether every settle is checked against {@link #settleViolation}: under assertions, as in tests. */
    private static final boolean CHECKED = LoadoutSpec.class.desiredAssertionStatus();

    /**
     * The first way {@code settle} breaks its own contract, or null: a withdrawal past the bank's stock,
     * a wear of an item the pack does not end with, or, when satisfiable, a leaving pack short of the
     * room asked for or one this loadout refuses.
     */
    @Nullable
    String settleViolation(Inventory pack, Bank bank, int keepFree, Settle settle, ServerData serverData) {
        for (InventorySlot sl : pack.slots()) {
            if (!sl.noted()
                    && sl.amount() > 1
                    && !serverData.isStackable(sl.item().id())) {
                return "the pack holds " + sl.amount() + " " + sl.item() + " in one slot, which does not stack";
            }
        }
        Inventory left = settle.projected();
        for (ItemId w : settle.manifest().withdraw().keySet()) {
            long drawn = left.count(NoteForm.BOTH, w.id()) - pack.count(NoteForm.BOTH, w.id());
            if (drawn > bank.count(w.id())) {
                return "withdraws " + drawn + " " + w + ", the bank holds " + bank.count(w.id());
            }
        }
        for (ItemId e : settle.manifest().equip()) {
            if (left.count(NoteForm.UNNOTED, e.id()) < 1) {
                return "wears " + e + ", which the pack does not end with";
            }
        }
        if (!settle.satisfiable()) {
            return null;
        }
        if (left.emptySlots() < Math.max(reserve, keepFree)) {
            return "leaves " + left.emptySlots() + " slots free, " + Math.max(reserve, keepFree) + " asked";
        }
        if (!satisfiedBy(left)) {
            return "leaves " + left.slots() + ", which it refuses";
        }
        return null;
    }

    // Per form key, what the stop may deposit of an item in one form for a band to withdraw in the other,
    // marking those slots claimed: notes no noted band names, for a band of the item, and the item beside
    // a noted band with no plain claimer. Null on a world without notes.
    private @Nullable Int2LongMap convertedForms(
            List<InventorySlot> slots, boolean[] claimed, boolean[] convertedSlot) {
        boolean forms = false;
        for (InventorySlot sl : slots) {
            forms |= sl.noted();
        }
        for (Requirement r : requirements) {
            forms |= r instanceof Item it && it.noted();
        }
        if (!forms) {
            return null;
        }
        Int2LongOpenHashMap converted = new Int2LongOpenHashMap();
        for (int i = 0; i < slots.size(); i++) {
            InventorySlot sl = slots.get(i);
            int id = sl.item().id();
            boolean converts = sl.noted()
                    ? !bandFor(id, true) && (bandFor(id, false) || pooled(id))
                    : bandFor(id, true) && !bandFor(id, false) && !pooled(id);
            if (!claimed[i] && !sl.equipped() && converts) {
                claimed[i] = true;
                convertedSlot[i] = true;
                converted.addTo(formKey(id, !sl.noted()), sl.amount());
            }
        }
        return converted;
    }

    private long poolFloors(int id, Inventory pack, Bank bank, ServerData serverData) {
        long floors = 0;
        for (Requirement r : requirements) {
            if (r instanceof Pool pool && pool.min() > 0 && rankOf(pool, id) >= 0) {
                long others = 0;
                for (ItemRef m : pool.items()) {
                    if (m.id() == id) {
                        continue;
                    }
                    long own = pack.count(NoteForm.BOTH, m.id()) + bank.count(m.id());
                    long itemTargets = itemTargets(m.id());
                    if (serverData.isStackable(m.id())) {
                        others += own > 0 && itemTargets == 0 && !itemNames(m.id()) ? 1 : 0;
                    } else {
                        others += Math.max(0, own - itemTargets);
                    }
                }
                floors += Math.max(0, pool.min() - others);
            }
        }
        return floors;
    }

    private long otherFormMins(int id, boolean noted) {
        long sum = 0;
        for (Requirement r : requirements) {
            if (r instanceof Item it && it.noted() != noted && it.item().id() == id) {
                sum = plus(sum, it.min());
            }
        }
        return sum;
    }

    private long itemTargets(int id) {
        long sum = 0;
        for (Requirement r : requirements) {
            if (r instanceof Item it && !it.noted() && it.item().id() == id) {
                sum = plus(sum, it.target());
            }
        }
        return sum;
    }

    private boolean itemNames(int id) {
        for (Requirement r : requirements) {
            if (r instanceof Item it && !it.noted() && it.item().id() == id) {
                return true;
            }
        }
        return false;
    }

    private boolean pooled(int id) {
        for (Requirement r : requirements) {
            if (r instanceof Pool pool && rankOf(pool, id) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean bandFor(int id, boolean noted) {
        for (Requirement r : requirements) {
            if (r instanceof Item it && it.item().id() == id && it.noted() == noted) {
                return true;
            }
        }
        return false;
    }

    private static int formKey(int id, boolean noted) {
        return noted ? ~id : id;
    }

    /** A hash of the bands in declaration order, for cache keys; equal loadouts hash equal. */
    public long fingerprint() {
        long h = 0xcbf29ce484222325L;
        h = fnv(h, bankWhenFull ? 1 : 0);
        h = fnv(h, anything ? 1 : 0);
        h = fnv(h, reserve);
        for (Requirement r : requirements) {
            if (r instanceof Item i) {
                h = fnv(h, 1);
                h = fnv(h, i.item().id());
                h = fnv(h, i.min());
                h = fnv(h, i.fill() ? -1 : i.target());
                h = fnv(h, i.max());
                h = fnv(h, i.worn() ? 1 : 0);
                if (i.noted()) {
                    h = fnv(h, 3);
                }
            } else if (r instanceof Pool p) {
                h = fnv(h, 2);
                for (ItemRef ref : p.items()) {
                    h = fnv(h, ref.id());
                }
                h = fnv(h, p.min());
                h = fnv(h, p.fill() ? -1 : p.target());
                h = fnv(h, p.max());
            }
        }
        return banksUnnamed ? fnv(h, 4) : h;
    }

    private void warnUnclaimed(int itemId) {
        if (!WARN_UNCLAIMED || WARNED.size() >= WARN_CAP) {
            return;
        }
        if (WARNED.add(fingerprint() * 31 + itemId)) {
            LOG.debug(
                    "loadout declares no requirement for item {} — the pack will be banked down to the "
                            + "loadout, so if a later step needs that item it must be in the loadout too: {}",
                    itemId,
                    this);
        }
    }

    private Settle build(
            Inventory pack,
            List<InventorySlot> slots,
            Finals finals,
            Set<ItemId> equip,
            ServerData serverData,
            boolean satisfiable) {
        for (InventorySlot sl : slots) {
            finals.touch(formKey(sl.item().id(), sl.noted()));
        }

        Map<ItemId, Long> withdraw = new LinkedHashMap<>();
        Map<ItemId, Long> deposit = new LinkedHashMap<>();
        Map<ItemId, Long> notes = new LinkedHashMap<>();
        for (int i = 0; i < finals.size(); i++) {
            int key = finals.id(i);
            if (key < 0 && finals.has(~key)) {
                continue;
            }
            int id = key < 0 ? ~key : key;
            long end = finals.of(id);
            long live = pack.count(NoteForm.UNNOTED, id);
            if (end != live) {
                (end > live ? withdraw : deposit).put(ItemId.getById(id), end);
            }
            long notedEnd = finals.of(~id);
            if (notedEnd != pack.count(NoteForm.NOTED, id)) {
                notes.put(ItemId.getById(id), notedEnd);
            }
        }
        // A band trimmed to none is not put on.
        Set<ItemId> wears = new LinkedHashSet<>();
        for (ItemId e : equip) {
            if (finals.of(e.id()) > 0) {
                wears.add(e);
            }
        }
        Manifest manifest = new Manifest(Map.copyOf(withdraw), Map.copyOf(deposit), Set.copyOf(wears), notes);
        return new Settle(satisfiable, manifest, Packs.apply(pack, manifest, serverData));
    }

    private static boolean keepsAny(boolean[] keep, List<InventorySlot> slots, int id) {
        for (int i = 0; i < slots.size(); i++) {
            if (keep[i] && slots.get(i).item().id() == id) {
                return true;
            }
        }
        return false;
    }

    private static void sortByAmountDesc(int[] idx, int count, List<InventorySlot> slots) {
        for (int i = 1; i < count; i++) {
            int v = idx[i];
            long amt = slots.get(v).amount();
            int j = i - 1;
            while (j >= 0 && slots.get(idx[j]).amount() < amt) {
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = v;
        }
    }

    private static void sortByRankThenAmountDesc(int[] idx, int[] rank, int count, List<InventorySlot> slots) {
        for (int i = 1; i < count; i++) {
            int vi = idx[i];
            int vr = rank[i];
            long amt = slots.get(vi).amount();
            int j = i - 1;
            while (j >= 0
                    && (rank[j] > vr || (rank[j] == vr && slots.get(idx[j]).amount() < amt))) {
                idx[j + 1] = idx[j];
                rank[j + 1] = rank[j];
                j--;
            }
            idx[j + 1] = vi;
            rank[j + 1] = vr;
        }
    }

    private static int rankOf(Pool pool, int itemId) {
        List<ItemRef> items = pool.items();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id() == itemId) {
                return i;
            }
        }
        return -1;
    }

    private static long fnv(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h = (h ^ ((v >>> (i * 8)) & 0xFF)) * 0x100000001b3L;
        }
        return h;
    }

    private static final class Finals {

        private int[] ids;
        private long[] counts;
        private int size;

        Finals(int capacity) {
            int c = Math.max(4, capacity);
            ids = new int[c];
            counts = new long[c];
        }

        private int indexOf(int id) {
            for (int i = 0; i < size; i++) {
                if (ids[i] == id) {
                    return i;
                }
            }
            return -1;
        }

        private int intern(int id) {
            int at = indexOf(id);
            if (at >= 0) {
                return at;
            }
            if (size == ids.length) {
                ids = Arrays.copyOf(ids, size * 2);
                counts = Arrays.copyOf(counts, size * 2);
            }
            ids[size] = id;
            counts[size] = 0;
            return size++;
        }

        void add(int id, long count) {
            counts[intern(id)] += count;
        }

        void touch(int id) {
            intern(id);
        }

        long of(int id) {
            int at = indexOf(id);
            return at < 0 ? 0 : counts[at];
        }

        boolean has(int id) {
            return indexOf(id) >= 0;
        }

        int size() {
            return size;
        }

        int id(int i) {
            return ids[i];
        }
    }

    private static final class Withdrawals {

        private int[] ids;
        private long[] amounts;
        private int size;

        Withdrawals(int capacity) {
            int c = Math.max(4, capacity);
            ids = new int[c];
            amounts = new long[c];
        }

        void add(int id, long amount) {
            if (size == ids.length) {
                ids = Arrays.copyOf(ids, size * 2);
                amounts = Arrays.copyOf(amounts, size * 2);
            }
            ids[size] = id;
            amounts[size] = amount;
            size++;
        }

        boolean holds(int id) {
            for (int i = 0; i < size; i++) {
                if (ids[i] == id) {
                    return true;
                }
            }
            return false;
        }

        /** What the rows draw of {@code id} from the bank, in either form. */
        long drawn(int id) {
            long sum = 0;
            for (int i = 0; i < size; i++) {
                if (ids[i] == id || ids[i] == ~id) {
                    sum += amounts[i];
                }
            }
            return sum;
        }

        int size() {
            return size;
        }

        int id(int row) {
            return ids[row];
        }

        long amount(int row) {
            return amounts[row];
        }
    }
}
