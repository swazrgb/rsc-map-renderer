package openrsc.nav;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.container.DelegatingInventory;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.NoteForm;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;

/**
 * The pack projected along a route. Each fold is a {@link DelegatingInventory} view over the pack
 * before it, never a copy; a view declares the ids it adjusts, so its slots, ids and counts agree,
 * and it compares by content.
 */
public final class Packs {

    private Packs() {}

    /**
     * {@code pack} after a stop's manifest, whose counts are absolute targets: a deposit lowers an item
     * to its target, a withdrawal raises it, an equip puts it on. The bank is not modelled.
     */
    public static Inventory apply(Inventory pack, Manifest manifest, ServerData defs) {
        return manifest.isEmpty() ? pack : new Applied(pack, manifest, defs);
    }

    /** {@code pack} with each of {@code wears} it carries on the body; an id it lacks changes nothing. */
    public static Inventory wear(Inventory pack, Set<Integer> wears, ServerData defs) {
        for (int id : wears) {
            if (pack.count(id) > 0 && pack.equippedCount(id) == 0) {
                return new Worn(pack, wears, defs);
            }
        }
        return pack;
    }

    /** {@code pack} after a hop took {@code fare}: stacks shrink, emptied slots go. */
    public static Inventory consume(Inventory pack, Map<Integer, Long> fare, ServerData defs) {
        return adjust(pack, Map.of(), fare, defs);
    }

    /** {@code pack} with {@code grants} added and {@code debits} taken, per id, never below none. */
    public static Inventory adjust(
            Inventory pack, Map<Integer, Long> grants, Map<Integer, Long> debits, ServerData defs) {
        return grants.isEmpty() && debits.isEmpty() ? pack : new Adjusted(pack, grants, debits, defs);
    }

    private static final class Applied extends DelegatingInventory {

        private final Inventory pack;
        private final Int2LongMap lower;
        private final Int2LongMap raise;
        private final Int2LongMap notes;

        private final IntSet moved;
        private final IntSet equips;

        Applied(Inventory pack, Manifest manifest, ServerData defs) {
            super(defs);
            this.pack = pack;
            this.lower = targets(manifest.deposit());
            this.raise = targets(manifest.withdraw());
            this.notes = targets(manifest.notes());
            IntSet ids = new IntOpenHashSet(lower.keySet());
            ids.addAll(raise.keySet());
            ids.addAll(notes.keySet());
            this.moved = IntSets.unmodifiable(ids);
            IntSet on = new IntOpenHashSet();
            for (ItemId id : manifest.equip()) {
                on.add(id.id());
            }
            this.equips = IntSets.unmodifiable(on);
        }

        private static Int2LongMap targets(Map<ItemId, Long> byItem) {
            Int2LongMap out = new Int2LongOpenHashMap(byItem.size());
            byItem.forEach((id, target) -> out.put(id.id(), (long) target));
            return out;
        }

        @Override
        protected Inventory delegate() {
            return pack;
        }

        @Override
        protected long adjustment(int itemId) {
            // Deposit and withdraw targets count the item itself, notes targets its notes.
            long noted = pack.count(NoteForm.NOTED, itemId);
            long count = pack.count(NoteForm.UNNOTED, itemId);
            long adj = 0;
            if (lower.containsKey(itemId) && count > lower.get(itemId)) {
                adj = lower.get(itemId) - count;
            }
            if (raise.containsKey(itemId) && count < raise.get(itemId)) {
                adj = raise.get(itemId) - count;
            }
            if (notes.containsKey(itemId)) {
                adj += notes.get(itemId) - noted;
            }
            return adj;
        }

        @Override
        protected long notedOf(int itemId) {
            if (notes.containsKey(itemId)) {
                return notes.get(itemId);
            }
            long noted = moved.contains(itemId) ? pack.count(NoteForm.NOTED, itemId) : 0;
            return noted > 0 ? noted : -1;
        }

        @Override
        protected IntSet adjustedIds() {
            return moved;
        }

        @Override
        protected boolean adjusts() {
            return !moved.isEmpty();
        }

        @Override
        protected IntSet wornIds() {
            return equips;
        }
    }

    private static final class Worn extends DelegatingInventory {

        private final Inventory pack;
        private final IntSet wears;

        Worn(Inventory pack, Set<Integer> wears, ServerData defs) {
            super(defs);
            this.pack = pack;
            this.wears = IntSets.unmodifiable(new IntOpenHashSet(wears));
        }

        @Override
        protected Inventory delegate() {
            return pack;
        }

        @Override
        protected long adjustment(int itemId) {
            return 0;
        }

        @Override
        protected boolean adjusts() {
            return false;
        }

        @Override
        protected IntSet wornIds() {
            return wears;
        }
    }

    private static final class Adjusted extends DelegatingInventory {

        private final Inventory pack;
        private final Int2LongMap net;
        private final IntSet ids;

        Adjusted(Inventory pack, Map<Integer, Long> grants, Map<Integer, Long> debits, ServerData defs) {
            super(defs);
            this.pack = pack;
            this.net = new Int2LongOpenHashMap(grants.size() + debits.size());
            grants.forEach((id, count) -> net.put((int) id, net.get((int) id) + count));
            debits.forEach((id, count) -> net.put((int) id, net.get((int) id) - count));
            this.ids = IntSets.unmodifiable(new IntOpenHashSet(net.keySet()));
        }

        @Override
        protected Inventory delegate() {
            return pack;
        }

        @Override
        protected long adjustment(int itemId) {
            return net.get(itemId);
        }

        @Override
        protected long notedOf(int itemId) {
            long noted = ids.contains(itemId) ? pack.count(NoteForm.NOTED, itemId) : 0;
            // A grant or a debit moves the item itself; its notes go only once it is gone.
            return noted > 0 ? Math.min(noted, Math.max(0, pack.count(NoteForm.BOTH, itemId) + net.get(itemId))) : -1;
        }

        @Override
        protected IntSet adjustedIds() {
            return ids;
        }

        @Override
        protected boolean adjusts() {
            return true;
        }
    }
}
