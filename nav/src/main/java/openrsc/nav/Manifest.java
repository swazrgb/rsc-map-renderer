package openrsc.nav;

import java.util.Map;
import java.util.Set;
import openrsc.gamedata.data.ItemId;

/**
 * What one bank stop does. Counts are absolute pack targets, not deltas: a {@code withdraw} entry
 * raises what the pack holds to that count and a {@code deposit} entry lowers it to that count, so
 * re-applying a manifest to a pack already settled does nothing.
 *
 * <p>{@code withdraw} and {@code deposit} count the item itself. {@code notes} holds the target
 * for the item's bank notes, for every item whose notes the stop changes; it is empty on a world
 * without bank notes.
 *
 * @param equip what is worn on leaving the stop that was not worn on arriving
 */
public record Manifest(
        Map<ItemId, Long> withdraw, Map<ItemId, Long> deposit, Set<ItemId> equip, Map<ItemId, Long> notes) {

    public static final Manifest EMPTY = new Manifest(Map.of(), Map.of(), Set.of());

    public Manifest {
        notes = Map.copyOf(notes);
    }

    public Manifest(Map<ItemId, Long> withdraw, Map<ItemId, Long> deposit, Set<ItemId> equip) {
        this(withdraw, deposit, equip, Map.of());
    }

    public boolean isEmpty() {
        return withdraw.isEmpty() && deposit.isEmpty() && equip.isEmpty() && notes.isEmpty();
    }

    @Override
    public String toString() {
        return "Manifest[withdraw=" + withdraw + ", deposit=" + deposit + ", equip=" + equip
                + (notes.isEmpty() ? "" : ", notes=" + notes) + "]";
    }
}
