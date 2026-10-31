package openrsc.bot.api.container;

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import openrsc.gamedata.ServerData;

/**
 * An {@link Inventory} that reads through to another with a per-item adjustment, without changing
 * it. Subclasses supply the delegate and the adjustment, and may also put items on the body
 * ({@link #wornIds()}) or decide an id's notes ({@link #notedOf}); otherwise worn and noted state is
 * the delegate's. An item that does not stack takes one slot per unit.
 *
 * <p>Worn slots sit off the pack when the delegate's do or the world has an equipment tab.
 */
public abstract class DelegatingInventory extends DelegatingContainer<InventorySlot, Inventory> implements Inventory {

    private final ServerData serverData;

    /**
     * Ids this view wears that the delegate may not. The first slot of each reads as worn, and
     * {@link #equippedCount} counts one of it, or the whole stack of a stackable. With worn slots off
     * the pack, what such a wear displaces ({@link ServerData#displaces}) reads as unworn.
     */
    protected IntSet wornIds() {
        return IntSets.EMPTY_SET;
    }

    protected DelegatingInventory(ServerData serverData) {
        this.serverData = Objects.requireNonNull(serverData, "serverData");
    }

    @Override
    public boolean wornOffPack() {
        return delegate().wornOffPack() || serverData.world().rules().equipmentTab();
    }

    @Override
    public long equippedCount(int itemId) {
        long base = delegate().equippedCount(itemId);
        long total = total(itemId);
        if (base > 0) {
            return strippedByAWear(itemId) ? 0 : Math.min(base, total);
        }
        if (total <= 0 || !wornIds().contains(itemId)) {
            return 0;
        }
        return serverData.isStackable(itemId) ? total : 1;
    }

    /**
     * How much of {@code itemId} this view holds as notes, or {@code -1} to keep the delegate's
     * layout. An id answered for reads as unnoted slots for the rest of its amount and one noted
     * stack.
     */
    protected long notedOf(int itemId) {
        return -1;
    }

    @Override
    protected long delegateTotal(int itemId) {
        return delegate().count(NoteForm.BOTH, itemId);
    }

    @Override
    public long count(int itemId) {
        return count(NoteForm.UNNOTED, itemId);
    }

    @Override
    public long count(NoteForm form, int itemId) {
        return switch (form) {
            case BOTH -> total(itemId);
            case NOTED -> noted(itemId);
            case UNNOTED -> total(itemId) - noted(itemId);
        };
    }

    private long noted(int itemId) {
        long own = notedOf(itemId);
        if (own >= 0) {
            return own;
        }
        long base = delegate().count(NoteForm.NOTED, itemId);
        if (base <= 0 || !adjustedIds().contains(itemId)) {
            return base;
        }
        long n = 0;
        for (InventorySlot s : slots()) {
            if (s.noted() && s.item().id() == itemId) {
                n += s.amount();
            }
        }
        return n;
    }

    @Override
    public int indexOf(int itemId) {
        return indexOf(NoteForm.UNNOTED, itemId);
    }

    @Override
    public int indexOf(NoteForm form, int itemId) {
        if (!adjusts()) {
            return delegate().indexOf(form, itemId);
        }
        List<InventorySlot> s = slots();
        for (int i = 0; i < s.size(); i++) {
            InventorySlot slot = s.get(i);
            if (slot.item().id() == itemId && form.admits(slot)) {
                return i;
            }
        }
        return -1;
    }

    private boolean strippedByAWear(int itemId) {
        IntSet worn = wornIds();
        if (worn.isEmpty() || worn.contains(itemId) || !wornOffPack()) {
            return false;
        }
        for (IntIterator it = worn.iterator(); it.hasNext(); ) {
            int put = it.nextInt();
            if (total(put) > 0 && serverData.displaces(put, itemId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<InventorySlot> slots() {
        List<InventorySlot> base = super.slots();
        IntSet worn = wornIds();
        if (worn.isEmpty()) {
            return base;
        }
        IntSet done = new IntOpenHashSet();
        for (InventorySlot s : base) {
            if (s.equipped()) {
                done.add(s.item().id());
            }
        }
        List<InventorySlot> out = null;
        for (int i = 0; i < base.size(); i++) {
            InventorySlot s = base.get(i);
            int id = s.item().id();
            if (!worn.contains(id) || !done.add(id)) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(base);
            }
            out.set(i, new InventorySlot(s.slot(), s.item(), s.amount(), true, s.noted()));
        }
        if (out != null && wornOffPack()) {
            for (int i = 0; i < out.size(); i++) {
                InventorySlot s = out.get(i);
                if (s.equipped() && strippedByAWear(s.item().id())) {
                    out.set(i, new InventorySlot(s.slot(), s.item(), s.amount(), false, s.noted()));
                }
            }
        }
        return out == null ? base : List.copyOf(out);
    }

    @Override
    protected void place(List<InventorySlot> out, InventorySlot template, int itemId, long total) {
        if (serverData.isStackable(itemId) || (template != null && template.noted())) {
            int amount = (int) Math.min(Integer.MAX_VALUE, total);
            out.add(template == null ? granted(out.size(), itemId, amount) : withAmount(template, out.size(), amount));
            return;
        }
        for (long c = 0; c < total; c++) {
            out.add(template == null ? granted(out.size(), itemId, 1) : withAmount(template, out.size(), 1));
        }
    }

    @Override
    protected void placeAdjusted(List<InventorySlot> out, InventorySlot template, int itemId, long total) {
        long noted = notedOf(itemId);
        if (noted < 0) {
            placeForm(out, template, itemId, total);
            return;
        }
        InventorySlot plain = template != null && !template.noted() ? template : null;
        if (plain == null) {
            for (InventorySlot s : delegate().slots()) {
                if (s.item().id() == itemId && !s.noted()) {
                    plain = s;
                    break;
                }
            }
        }
        if (total - noted > 0) {
            placeForm(out, plain, itemId, total - noted);
        }
        if (noted > 0) {
            out.add(new InventorySlot(out.size(), itemId, (int) Math.min(Integer.MAX_VALUE, noted), false, true));
        }
    }

    private void placeForm(List<InventorySlot> out, InventorySlot template, int itemId, long total) {
        long worn = wornOffPack() ? Math.min(total, delegate().equippedCount(itemId)) : 0;
        if (worn <= 0 && (template == null || !template.equipped() || !wornOffPack())) {
            place(out, template, itemId, total);
            return;
        }
        boolean noted = template != null && template.noted();
        if (total - worn > 0) {
            place(out, new InventorySlot(0, itemId, 1, false, noted), itemId, total - worn);
        }
        if (worn > 0) {
            place(out, new InventorySlot(0, itemId, 1, true, false), itemId, worn);
        }
    }

    @Override
    protected InventorySlot withAmount(InventorySlot slot, int idx, int amount) {
        return new InventorySlot(idx, slot.item(), amount, slot.equipped(), slot.noted());
    }

    @Override
    protected InventorySlot granted(int idx, int itemId, int amount) {
        return new InventorySlot(idx, itemId, amount, false, false);
    }
}
