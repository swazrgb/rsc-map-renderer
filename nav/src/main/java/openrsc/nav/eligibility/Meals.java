package openrsc.nav.eligibility;

import java.util.Set;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;

/**
 * The foods that let a Hits gate read the base level instead of the current one: carried or banked,
 * they are hits the player can eat back before the gate.
 *
 * @see EligibilityInputs.Fact.Kind#SKILL_AT_LEAST
 */
public final class Meals {

    public static final Set<Integer> IDS = Set.of(
            ItemId.SHARK.id(),
            ItemId.SWORDFISH.id(),
            ItemId.BASS.id(),
            ItemId.LOBSTER.id(),
            ItemId.TUNA.id(),
            ItemId.SALMON.id(),
            ItemId.TROUT.id(),
            ItemId.BREAD.id(),
            ItemId.SLICE_OF_CAKE.id(),
            ItemId.PARTIAL_CAKE.id(),
            ItemId.CAKE.id(),
            ItemId.COOKEDMEAT.id(),
            ItemId.SHRIMP.id(),
            ItemId.ANCHOVIES.id());

    private Meals() {}

    /** Whether {@code pack} holds an unnoted meal. */
    public static boolean carried(Inventory pack) {
        for (InventorySlot s : pack.slots()) {
            if (!s.noted() && IDS.contains(s.id())) {
                return true;
            }
        }
        return false;
    }

    /** The banked meal that heals the most, or {@code -1} when the bank holds none. */
    public static int bestBanked(Bank bank, ServerData defs) {
        int best = -1;
        int bestHeal = 0;
        for (BankSlot s : bank.slots()) {
            int id = s.item().id();
            int heal = IDS.contains(id) ? defs.healFor(id) : 0;
            if (heal > bestHeal) {
                best = id;
                bestHeal = heal;
            }
        }
        return best;
    }
}
