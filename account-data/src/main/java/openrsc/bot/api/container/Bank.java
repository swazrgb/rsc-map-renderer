package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.util.List;

/** A bank's contents. A bank slot holds one id in any amount and has no worn or noted form. */
public interface Bank extends Container<BankSlot> {

    /**
     * The bank size of a members world without custom banks, used when the world's own is not known.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L147">World.World</a>
     */
    int CAPACITY = 192;

    /** The shared empty bank at {@link #CAPACITY}. */
    static Bank empty() {
        return EmptyContainers.BANK;
    }

    static Bank of(List<BankSlot> slots) {
        return of(CAPACITY, slots);
    }

    /** A frozen bank of {@code slots}, which must be in slot order. */
    static Bank of(int capacity, List<BankSlot> slots) {
        if (slots.isEmpty() && capacity == CAPACITY) {
            return empty();
        }

        MutableBank m = new MutableBank();
        m.setCapacity(capacity);
        m.setAll(slots);
        return m.snapshot();
    }

    final class Frozen extends ImmutableContainer<BankSlot> implements Bank {

        Frozen(int capacity, List<BankSlot> slots, Int2LongMap totals, Int2IntMap firstIdx) {
            super(capacity, slots, totals, firstIdx);
        }
    }
}
