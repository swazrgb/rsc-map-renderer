package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.util.List;

/** A bank being written slot by slot; read it through {@link #snapshot()}. */
public final class MutableBank extends MutableContainer<BankSlot, Bank> {

    public MutableBank() {
        super(Bank.CAPACITY);
    }

    /** Sets the bank size, for a world whose bank is not {@link Bank#CAPACITY} slots. */
    public void setCapacity(int capacity) {
        setCapacityInternal(capacity);
    }

    /**
     * Applies an add the server made without sending an update, the way it adds: onto the first
     * stack of the item that is not full, with what passes {@link Integer#MAX_VALUE} going to a new
     * stack at the end, else as a new stack at the end. A non-positive {@code amount} does nothing,
     * and so does a new stack in a bank already holding {@link #capacity()} slots; an overflow stack
     * is added past it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L44">Bank.add</a>
     */
    public void added(int itemId, int amount) {
        if (amount <= 0) {
            return;
        }
        List<BankSlot> slots = snapshot().slots();
        for (BankSlot s : slots) {
            if (s.item().id() != itemId || s.amount() == Integer.MAX_VALUE) {
                continue;
            }
            int room = Integer.MAX_VALUE - s.amount();
            setSlot(s.slot(), new BankSlot(s.slot(), itemId, s.amount() + Math.min(room, amount)));
            if (amount > room) {
                setSlot(slots.size(), new BankSlot(slots.size(), itemId, amount - room));
            }
            return;
        }
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L77
        if (slots.size() >= capacity()) {
            return;
        }
        setSlot(slots.size(), new BankSlot(slots.size(), itemId, amount));
    }

    @Override
    protected Bank newSnapshot(int capacity, List<BankSlot> slots, Int2LongMap totals, Int2IntMap firstIdx) {
        return new Bank.Frozen(capacity, slots, totals, firstIdx);
    }

    @Override
    protected BankSlot reindex(BankSlot slot, int idx) {
        return new BankSlot(idx, slot.item(), slot.amount());
    }
}
