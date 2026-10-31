package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.List;

/**
 * A container that reads through to {@link #delegate()} with a per-item adjustment applied, without
 * changing it. Every read reflects the adjustment, slots and indices included, and nothing is
 * cached: a read of an adjusted id's slots rebuilds them.
 */
abstract class DelegatingContainer<S extends Slot, C extends Container<S>> implements Container<S> {

    @Override
    public int capacity() {
        return delegate().capacity();
    }

    @Override
    public long count(int itemId) {
        return total(itemId);
    }

    /** The view's whole amount of {@code itemId}: the delegate's, adjusted, and never below zero. */
    protected final long total(int itemId) {
        long base = delegateTotal(itemId);
        long adj = adjustment(itemId);
        return adj == 0 ? base : Math.max(0, base + adj);
    }

    /** The delegate's whole amount of {@code itemId}, which the adjustment applies to. */
    protected long delegateTotal(int itemId) {
        return delegate().count(itemId);
    }

    @Override
    public IntSet itemIds() {
        IntSet adjusted = adjustedIds();
        if (adjusted.isEmpty()) {
            return delegate().itemIds();
        }

        IntSet out = new IntOpenHashSet(delegate().itemIds());
        for (IntIterator it = adjusted.iterator(); it.hasNext(); ) {
            out.add(it.nextInt());
        }
        out.removeIf((int id) -> total(id) <= 0);
        return IntSets.unmodifiable(out);
    }

    @Override
    public int indexOf(int itemId) {
        if (!adjusts()) {
            return delegate().indexOf(itemId);
        }

        List<S> s = slots();
        for (int i = 0; i < s.size(); i++) {
            if (s.get(i).id() == itemId) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public List<S> slots() {
        IntSet adjusted = adjustedIds();
        if (adjusted.isEmpty()) {
            return delegate().slots();
        }

        List<S> out = new ArrayList<>(delegate().usedSlots() + adjusted.size());
        IntSet placed = new IntOpenHashSet(adjusted.size());
        for (S slot : delegate().slots()) {
            int id = slot.id();
            long want = adjusted.contains(id) ? total(id) : slot.amount();
            if (adjusted.contains(id)) {
                if (!placed.add(id)) {
                    continue;
                }
            }
            if (want <= 0) {
                continue;
            }
            if (adjusted.contains(id)) {
                placeAdjusted(out, slot, id, want);
            } else {
                place(out, slot, id, want);
            }
        }

        for (IntIterator it = adjusted.iterator(); it.hasNext(); ) {
            int id = it.nextInt();
            if (placed.contains(id)) {
                continue;
            }
            long total = total(id);
            if (total > 0) {
                placeAdjusted(out, null, id, total);
            }
        }

        return List.copyOf(out);
    }

    @Override
    public boolean equals(Object o) {
        return this == o
                || (o instanceof Container<?> that && capacity() == that.capacity() && slots().equals(that.slots()));
    }

    @Override
    public int hashCode() {
        return 31 * capacity() + slots().hashCode();
    }

    /** The container read through; asked on every read, so it may change between reads. */
    protected abstract C delegate();

    /** The signed change to {@code itemId}'s amount: negative spends, positive grants. */
    protected abstract long adjustment(int itemId);

    /**
     * Every id {@link #adjustment} may be non-zero for. Ids the delegate does not hold must be here
     * for their grants to show in {@link #slots()} and {@link #itemIds()}.
     */
    protected IntSet adjustedIds() {
        return IntSets.EMPTY_SET;
    }

    /** Whether any id is adjusted; override when that is cheaper to answer than building the set. */
    protected boolean adjusts() {
        return !adjustedIds().isEmpty();
    }

    /** {@link #place} for an adjusted id, whose slots are all rebuilt from its first slot or none. */
    protected void placeAdjusted(List<S> out, S template, int itemId, long total) {
        place(out, template, itemId, total);
    }

    /** {@code slot} moved to index {@code idx} with {@code amount}. */
    protected abstract S withAmount(S slot, int idx, int amount);

    /** A new slot for an id the delegate does not hold. */
    protected abstract S granted(int idx, int itemId, int amount);

    /**
     * Appends to {@code out} the slots that {@code total} of {@code itemId} occupies, built from
     * {@code template}, or with {@link #granted} when {@code template} is {@code null}.
     */
    protected abstract void place(List<S> out, S template, int itemId, long total);
}
