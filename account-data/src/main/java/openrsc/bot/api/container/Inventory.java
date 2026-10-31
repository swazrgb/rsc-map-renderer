package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongMaps;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemRef;

/**
 * What an account carries, worn items included as slots flagged {@link InventorySlot#equipped()}.
 * On a world with an equipment tab the worn items are listed too but take no pack slot
 * ({@link #wornOffPack()}), so count room with {@link #usedSlots()}, not {@code slots().size()}.
 *
 * <p>A bank note shares its item's id and differs only by the slot's {@link InventorySlot#noted()}
 * flag. Every per-item query without a {@link NoteForm} counts the item itself
 * ({@link NoteForm#UNNOTED}); {@link NoteForm#BOTH} counts notes too.
 */
public interface Inventory extends Container<InventorySlot> {

    /**
     * The pack slots of every inventory.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L30">Inventory.MAX_SIZE</a>
     */
    int CAPACITY = 30;

    static Inventory empty() {
        return EmptyContainers.INVENTORY;
    }

    static Inventory of(List<InventorySlot> slots) {
        return of(slots, false);
    }

    /** A frozen inventory of {@code slots}, which must be in slot order. */
    static Inventory of(List<InventorySlot> slots, boolean wornOffPack) {
        if (slots.isEmpty()) {
            return empty();
        }

        MutableInventory m = new MutableInventory(wornOffPack);
        m.setAll(slots);
        return m.snapshot();
    }

    /**
     * Whether worn slots take no pack slot, as on a world with an equipment tab. When false, a worn
     * item keeps its pack slot.
     */
    default boolean wornOffPack() {
        return false;
    }

    default boolean takesPackSlot(InventorySlot slot) {
        return !(slot.equipped() && wornOffPack());
    }

    @Override
    default int usedSlots() {
        List<InventorySlot> all = slots();
        if (!wornOffPack()) {
            return all.size();
        }
        int n = 0;
        for (InventorySlot s : all) {
            if (!s.equipped()) {
                n++;
            }
        }
        return n;
    }

    /** How much of {@code itemId} is held in {@code form}, worn copies included. */
    long count(NoteForm form, int itemId);

    @Override
    default long count(int itemId) {
        return count(NoteForm.UNNOTED, itemId);
    }

    @Override
    default long count(ItemRef item) {
        return count(NoteForm.UNNOTED, item.id());
    }

    default boolean has(NoteForm form, int itemId) {
        return count(form, itemId) > 0;
    }

    @Override
    default boolean has(int itemId) {
        return has(NoteForm.UNNOTED, itemId);
    }

    @Override
    default boolean has(ItemRef item) {
        return has(NoteForm.UNNOTED, item.id());
    }

    default long countAny(NoteForm form, int... ids) {
        long total = 0;
        for (int id : ids) {
            total += count(form, id);
        }
        return total;
    }

    default long countAny(NoteForm form, ItemRef... refs) {
        long total = 0;
        for (ItemRef ref : refs) {
            total += count(form, ref.id());
        }
        return total;
    }

    @Override
    default long countAny(int... ids) {
        return countAny(NoteForm.UNNOTED, ids);
    }

    @Override
    default long countAny(ItemRef... refs) {
        return countAny(NoteForm.UNNOTED, refs);
    }

    /** The lowest slot index holding {@code itemId} in {@code form}, or {@code -1}. */
    int indexOf(NoteForm form, int itemId);

    @Override
    default int indexOf(int itemId) {
        return indexOf(NoteForm.UNNOTED, itemId);
    }

    /** The lowest-index slot holding any of {@code ids} in {@code form}, or {@code null}. */
    default InventorySlot firstOf(NoteForm form, int... ids) {
        int at = Integer.MAX_VALUE;
        for (int id : ids) {
            int i = indexOf(form, id);
            if (i >= 0 && i < at) {
                at = i;
            }
        }
        return at == Integer.MAX_VALUE ? null : slot(at);
    }

    default InventorySlot firstOf(NoteForm form, ItemRef... refs) {
        int at = Integer.MAX_VALUE;
        for (ItemRef ref : refs) {
            int i = indexOf(form, ref.id());
            if (i >= 0 && i < at) {
                at = i;
            }
        }
        return at == Integer.MAX_VALUE ? null : slot(at);
    }

    @Override
    default InventorySlot firstOf(int... ids) {
        return firstOf(NoteForm.UNNOTED, ids);
    }

    @Override
    default InventorySlot firstOf(ItemRef... refs) {
        return firstOf(NoteForm.UNNOTED, refs);
    }

    /** The first slot holding {@code itemId} in {@code form} that is not worn, or {@code null}. */
    default InventorySlot firstLoose(NoteForm form, int itemId) {
        for (InventorySlot s : slots()) {
            if (s.item().id() == itemId && form.admits(s) && !s.equipped()) {
                return s;
            }
        }
        return null;
    }

    default InventorySlot firstLoose(int itemId) {
        return firstLoose(NoteForm.UNNOTED, itemId);
    }

    /** How much of {@code itemId} in {@code form} takes pack slots: worn copies off the pack excluded. */
    default long packCount(NoteForm form, int itemId) {
        return wornOffPack() ? unequippedCount(form, itemId) : count(form, itemId);
    }

    default long packCount(int itemId) {
        return packCount(NoteForm.UNNOTED, itemId);
    }

    /** How much of {@code itemId} is worn; a worn copy is never a note. */
    long equippedCount(int itemId);

    @Override
    default long newSlotsFor(ServerData defs, int itemId, long count) {
        if (count <= 0) {
            return 0;
        }
        if (!defs.isStackable(itemId)) {
            return count;
        }
        return packCount(itemId) > 0 ? 0 : 1;
    }

    /**
     * The slots receiving {@code plan} (item id to amount) in a trade would newly occupy. An item that
     * {@link ServerData#ridesAsNotes rides as notes} takes one slot, or none when its notes are already
     * held; the rest are counted as {@link #newSlotsFor(ServerData, Map)} does.
     */
    default long tradeSlotsFor(ServerData defs, Map<Integer, Long> plan) {
        long slots = 0;
        Map<Integer, Long> plain = null;
        for (Map.Entry<Integer, Long> e : plan.entrySet()) {
            int id = e.getKey();
            if (e.getValue() <= 0) {
                continue;
            }
            if (defs.ridesAsNotes(id)) {
                slots += has(NoteForm.NOTED, id) ? 0 : 1;
            } else {
                if (plain == null) {
                    plain = new LinkedHashMap<>();
                }
                plain.put(id, e.getValue());
            }
        }
        return plain == null ? slots : slots + newSlotsFor(defs, plain);
    }

    /** How much of {@code itemId} in {@code form} is held but not worn. */
    default long unequippedCount(NoteForm form, int itemId) {
        long worn = form == NoteForm.NOTED ? 0 : equippedCount(itemId);
        return Math.max(0, count(form, itemId) - worn);
    }

    default long unequippedCount(NoteForm form, ItemRef item) {
        return unequippedCount(form, item.id());
    }

    default long unequippedCount(int itemId) {
        return unequippedCount(NoteForm.UNNOTED, itemId);
    }

    default boolean isEquipped(int itemId) {
        return equippedCount(itemId) > 0;
    }

    final class Frozen extends ImmutableContainer<InventorySlot> implements Inventory {

        private final Int2LongMap equippedTotals;

        private final Int2LongMap notedTotals;

        private final boolean wornOffPack;

        private final int packSlots;

        Frozen(int capacity, List<InventorySlot> slots, Int2LongMap totals, Int2IntMap firstIdx, boolean wornOffPack) {
            super(capacity, slots, totals, firstIdx);
            this.wornOffPack = wornOffPack;
            Int2LongMap worn = null;
            Int2LongMap notes = null;
            int offPack = 0;
            for (InventorySlot s : slots) {
                if (s.noted()) {
                    if (notes == null) {
                        notes = new Int2LongOpenHashMap();
                        notes.defaultReturnValue(0);
                    }
                    notes.mergeLong(s.item().id(), s.amount(), Long::sum);
                }
                if (s.equipped()) {
                    offPack++;
                    if (worn == null) {
                        worn = new Int2LongOpenHashMap();
                        worn.defaultReturnValue(0);
                    }
                    worn.mergeLong(s.item().id(), s.amount(), Long::sum);
                }
            }
            this.equippedTotals = worn == null ? Int2LongMaps.EMPTY_MAP : worn;
            this.notedTotals = notes == null ? Int2LongMaps.EMPTY_MAP : notes;
            this.packSlots = slots.size() - (wornOffPack ? offPack : 0);
        }

        @Override
        public long equippedCount(int itemId) {
            return equippedTotals.get(itemId);
        }

        @Override
        public long count(int itemId) {
            return count(NoteForm.UNNOTED, itemId);
        }

        @Override
        public long count(NoteForm form, int itemId) {
            return switch (form) {
                case BOTH -> super.count(itemId);
                case NOTED -> notedTotals.get(itemId);
                case UNNOTED -> super.count(itemId) - notedTotals.get(itemId);
            };
        }

        @Override
        public int indexOf(int itemId) {
            return indexOf(NoteForm.UNNOTED, itemId);
        }

        @Override
        public int indexOf(NoteForm form, int itemId) {
            int first = super.indexOf(itemId);
            if (form == NoteForm.BOTH || first < 0) {
                return first;
            }
            if (notedTotals.get(itemId) == 0) {
                return form == NoteForm.NOTED ? -1 : first;
            }
            List<InventorySlot> all = slots();
            for (int i = first; i < all.size(); i++) {
                InventorySlot s = all.get(i);
                if (s.item().id() == itemId && form.admits(s)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public boolean wornOffPack() {
            return wornOffPack;
        }

        @Override
        public int usedSlots() {
            return packSlots;
        }
    }
}
