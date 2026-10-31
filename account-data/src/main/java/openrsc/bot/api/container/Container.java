package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemRef;

/**
 * The slots of a bank or an inventory. Item ids are the server's raw catalog ids; the {@link ItemRef}
 * overloads read the same.
 *
 * <p>The instances {@link MutableContainer#snapshot()} and the {@code of} factories build are
 * immutable, so they may be read from any thread and kept. A {@link DelegatingInventory} view is
 * only as stable as the container it reads through. Two containers
 * are equal when their capacities and slot lists are, whichever implementation backs them.
 *
 * <p>An {@link Inventory}'s per-item queries leave bank notes out unless a {@link NoteForm} asks for
 * them.
 */
public interface Container<S extends Slot> extends ItemCount {

    /** The number of slots, occupied or not; {@link #usedSlots()} is how many are occupied. */
    int capacity();

    /**
     * The occupied slots, in index order, with no gaps: each entry's {@link Slot#slot()} is its
     * position. An {@link Inventory} whose worn items sit off the pack lists those too, so this can
     * hold more entries than {@link #usedSlots()}.
     */
    List<S> slots();

    /** The lowest slot index holding {@code itemId}, or {@code -1}. */
    int indexOf(int itemId);

    /** The distinct ids held, unordered and read-only. */
    IntSet itemIds();

    /** The occupied slots that count against {@link #capacity()}. */
    default int usedSlots() {
        return slots().size();
    }

    /** Free slots; never negative, even when more slots are used than the capacity allows. */
    default int emptySlots() {
        return Math.max(0, capacity() - usedSlots());
    }

    default boolean isEmpty() {
        return slots().isEmpty();
    }

    default boolean isFull() {
        return usedSlots() >= capacity();
    }

    /** The slot at {@code idx}, or {@code null} when it is out of range. */
    default S slot(int idx) {
        List<S> s = slots();
        return idx < 0 || idx >= s.size() ? null : s.get(idx);
    }

    @Override
    default boolean has(int itemId) {
        return count(itemId) > 0;
    }

    /** Whether {@code count} more of {@code itemId} fit in the free slots. */
    default boolean hasSpaceFor(ServerData defs, int itemId, long count) {
        return newSlotsFor(defs, itemId, count) <= emptySlots();
    }

    /**
     * The slots that adding {@code count} of {@code itemId} would newly occupy. Here every id stacks,
     * as in a bank: none when the id is already held, else one. {@link Inventory} counts a slot per
     * unit of an item that does not stack.
     */
    default long newSlotsFor(ServerData defs, int itemId, long count) {
        if (count <= 0) {
            return 0;
        }
        return has(itemId) ? 0 : 1;
    }

    /** The slots that adding all of {@code basket} (item id to amount) at once would newly occupy. */
    default long newSlotsFor(ServerData defs, Map<Integer, Long> basket) {
        long slots = 0;
        for (Map.Entry<Integer, Long> e : basket.entrySet()) {
            slots += newSlotsFor(defs, e.getKey(), e.getValue());
        }
        return slots;
    }

    /** The amount held summed over {@code ids}; an id listed twice is counted twice. */
    default long countAny(int... ids) {
        long total = 0;
        for (int id : ids) {
            total += count(id);
        }

        return total;
    }

    default long countAny(ItemRef... refs) {
        long total = 0;
        for (ItemRef ref : refs) {
            total += count(ref.id());
        }

        return total;
    }

    /** The lowest-index slot holding any of {@code ids}, or {@code null}. */
    default S firstOf(int... ids) {
        int at = Integer.MAX_VALUE;
        for (int id : ids) {
            int i = indexOf(id);
            if (i >= 0 && i < at) {
                at = i;
            }
        }

        return at == Integer.MAX_VALUE ? null : slot(at);
    }

    default S firstOf(ItemRef... refs) {
        int at = Integer.MAX_VALUE;
        for (ItemRef ref : refs) {
            int i = indexOf(ref.id());
            if (i >= 0 && i < at) {
                at = i;
            }
        }

        return at == Integer.MAX_VALUE ? null : slot(at);
    }
}
