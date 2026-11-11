package openrsc.bot.api.eligibility;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import openrsc.bot.api.container.ItemCount;
import openrsc.bot.api.container.NoteForm;

/** What one account holds of one item: in its inventory, noted or not, and in its bank. */
public record Holding(String username, int itemId, int inventory, int bank, boolean equipped) {

    /** Most held first, then by username. */
    public static final Comparator<Holding> MOST_FIRST =
            Comparator.comparingInt(Holding::total).reversed().thenComparing(Holding::username);

    public int total() {
        return inventory + bank;
    }

    /** One row per item the account holds anywhere. */
    public static List<Holding> rowsOf(AccountFacts s) {
        IntSet ids = new IntOpenHashSet(s.inventory().itemIds());
        ids.addAll(s.bank().itemIds());
        List<Holding> out = new ArrayList<>();
        for (int id : ids) {
            out.add(of(s, id));
        }
        return out;
    }

    /** The account's holding of the item, zero where it has none. */
    public static Holding of(AccountFacts s, int itemId) {
        long inv = s.inventory().count(NoteForm.BOTH, itemId);
        long bank = s.bank().count(itemId);
        return new Holding(
                s.username(),
                itemId,
                ItemCount.toInt(inv),
                ItemCount.toInt(bank),
                s.inventory().isEquipped(itemId));
    }
}
