package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The writable side of a container: a list with no gaps, where a removal moves the later slots
 * down. Not thread-safe; other threads read it through {@link #snapshot()}.
 */
public abstract class MutableContainer<S extends Slot, C extends Container<S>> {

    private int capacity;

    private final List<S> slots;
    private final Int2LongMap totals = new Int2LongOpenHashMap();
    private final Int2IntMap firstIdx = new Int2IntOpenHashMap();
    private C snapshot;

    protected MutableContainer(int capacity) {
        this.capacity = capacity;
        this.slots = new ArrayList<>(capacity);
        this.totals.defaultReturnValue(0);
        this.firstIdx.defaultReturnValue(-1);
    }

    /** The current contents, frozen. The same instance is returned until the next change. */
    public C snapshot() {
        C cached = snapshot;
        if (cached == null) {
            cached = newSnapshot(capacity, slots, totals, firstIdx);
            snapshot = cached;
        }

        return cached;
    }

    public int capacity() {
        return capacity;
    }

    public void setAll(List<? extends S> contents) {
        snapshot = null;
        slots.clear();
        totals.clear();
        firstIdx.clear();
        for (int i = 0; i < contents.size(); i++) {
            setSlot(i, contents.get(i));
        }
    }

    /**
     * Writes {@code slot} at {@code idx}, re-indexed to it if needed. An {@code idx} equal to the size
     * appends; a negative one or one past the size is ignored.
     */
    public void setSlot(int idx, S slot) {
        Objects.requireNonNull(slot, "slot: use removeSlot(int) to remove");
        if (idx < 0 || idx > slots.size()) {
            return;
        }

        snapshot = null;
        S seated = seat(slot, idx);
        if (idx == slots.size()) {
            slots.add(seated);
        } else {
            S prev = slots.set(idx, seated);
            debit(prev);
            untrackIndex(prev.item().id(), idx);
        }

        credit(seated);
        trackIndex(seated.item().id(), idx);
    }

    /** Removes the slot at {@code idx} and moves every later slot down one. Out of range is ignored. */
    public void removeSlot(int idx) {
        if (idx < 0 || idx >= slots.size()) {
            return;
        }

        snapshot = null;
        S removed = slots.remove(idx);
        debit(removed);
        for (int i = idx; i < slots.size(); i++) {
            slots.set(i, reindex(slots.get(i), i));
        }
        shiftIndexesDown(idx, removed.item().id());
    }

    protected void setCapacityInternal(int capacity) {
        if (this.capacity == capacity) {
            return;
        }

        this.capacity = capacity;
        snapshot = null;
    }

    protected abstract C newSnapshot(int capacity, List<S> slots, Int2LongMap totals, Int2IntMap firstIdx);

    /** A copy of {@code slot} whose {@link Slot#slot()} is {@code idx}. */
    protected abstract S reindex(S slot, int idx);

    private S seat(S slot, int idx) {
        return slot.slot() == idx ? slot : reindex(slot, idx);
    }

    private void credit(S slot) {
        totals.mergeLong(slot.item().id(), slot.amount(), Long::sum);
    }

    private void debit(S slot) {
        if (totals.mergeLong(slot.item().id(), -slot.amount(), Long::sum) <= 0) {
            totals.remove(slot.item().id());
        }
    }

    private void trackIndex(int itemId, int idx) {
        int first = firstIdx.get(itemId);
        if (first == -1 || idx < first) {
            firstIdx.put(itemId, idx);
        }
    }

    private void untrackIndex(int itemId, int idx) {
        if (firstIdx.get(itemId) != idx) {
            return;
        }

        findFirstFrom(itemId, idx);
    }

    private void shiftIndexesDown(int idx, int removed) {
        for (Int2IntMap.Entry e : firstIdx.int2IntEntrySet()) {
            if (e.getIntValue() > idx) {
                e.setValue(e.getIntValue() - 1);
            }
        }

        if (firstIdx.get(removed) == idx) {
            findFirstFrom(removed, idx);
        }
    }

    private void findFirstFrom(int itemId, int idx) {
        for (int i = idx; i < slots.size(); i++) {
            if (slots.get(i).item().id() == itemId) {
                firstIdx.put(itemId, i);
                return;
            }
        }

        firstIdx.remove(itemId);
    }
}
