package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntMaps;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.List;

/** The frozen {@link Container}: a copy of the contents and of the per-item totals and first indices. */
abstract class ImmutableContainer<S extends Slot> implements Container<S> {

    private final int capacity;
    private final List<S> slots;
    private final Int2LongMap totals;
    private final Int2IntMap firstIdx;
    private final IntSet itemIds;

    ImmutableContainer(int capacity, List<S> slots, Int2LongMap totals, Int2IntMap firstIdx) {
        this.capacity = capacity;
        this.slots = List.copyOf(slots);
        if (totals.isEmpty()) {
            this.totals = Int2LongMaps.EMPTY_MAP;
            this.firstIdx = Int2IntMaps.EMPTY_MAP;
            this.itemIds = IntSets.EMPTY_SET;
            return;
        }
        this.totals = new Int2LongOpenHashMap(totals);
        // fastutil's copy constructors do not carry the default return value over.
        this.totals.defaultReturnValue(0);
        this.firstIdx = new Int2IntOpenHashMap(firstIdx);
        this.firstIdx.defaultReturnValue(-1);
        this.itemIds = IntSets.unmodifiable(this.totals.keySet());
    }

    @Override
    public boolean equals(Object o) {
        return this == o
                || (o instanceof Container<?> that && capacity == that.capacity() && slots.equals(that.slots()));
    }

    @Override
    public int hashCode() {
        return 31 * capacity + slots.hashCode();
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int usedSlots() {
        return slots.size();
    }

    @Override
    public List<S> slots() {
        return slots;
    }

    @Override
    public S slot(int idx) {
        return idx < 0 || idx >= slots.size() ? null : slots.get(idx);
    }

    @Override
    public long count(int itemId) {
        return totals.get(itemId);
    }

    @Override
    public int indexOf(int itemId) {
        return firstIdx.get(itemId);
    }

    @Override
    public IntSet itemIds() {
        return itemIds;
    }
}
