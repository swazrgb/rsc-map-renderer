package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.util.List;

/** An inventory being written slot by slot; read it through {@link #snapshot()}. */
public final class MutableInventory extends MutableContainer<InventorySlot, Inventory> {

    private final boolean wornOffPack;

    public MutableInventory() {
        this(false);
    }

    /** An empty inventory whose snapshots answer {@code wornOffPack}; see {@link Inventory#wornOffPack()}. */
    public MutableInventory(boolean wornOffPack) {
        super(Inventory.CAPACITY);
        this.wornOffPack = wornOffPack;
    }

    @Override
    protected Inventory newSnapshot(int capacity, List<InventorySlot> slots, Int2LongMap totals, Int2IntMap firstIdx) {
        return new Inventory.Frozen(capacity, slots, totals, firstIdx, wornOffPack);
    }

    @Override
    protected InventorySlot reindex(InventorySlot slot, int idx) {
        return new InventorySlot(idx, slot.item(), slot.amount(), slot.equipped(), slot.noted());
    }
}
