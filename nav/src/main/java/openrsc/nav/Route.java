package openrsc.nav;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.NoteForm;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;

/**
 * A planned trip as the sequence of things to do on the way, not a tile path: walk across a seam,
 * take a hop, stop at a bank, arrive. Between steps the follower finds its own way, so a route
 * stays valid while the walk around it changes.
 *
 * <p>A route holds only what the {@link PlanRequest} did not: the account, policy and starting pack
 * stay with the request.
 *
 * @param steps in order; the last is always an {@link Arrive}
 * @param totalCostDeci how long the trip takes, in deci-ticks (10 to a tick)
 * @param debits item id to how many of it the trip's hops consume in all
 */
public record Route(List<Step> steps, int totalCostDeci, Map<Integer, Long> debits) {

    /**
     * One thing to do on the way. Every step carries the pack the planner projects for it: the
     * starting pack carried through each earlier stop and hop.
     */
    public sealed interface Step permits Cross, Hop, BankStop, Arrive {
        /** What the account holds while it makes this step. */
        Inventory pack();
    }

    /**
     * Cross from one cluster of the planner's map into the next anywhere on {@code span}. Two spans
     * on one seam can lead into parts of the next cluster that do not connect, so the span matters.
     */
    public record Cross(Rect span, Inventory pack) implements Step {}

    /**
     * Take {@code transport} (its {@link Transport#name()}) from {@code entry} to {@code landing}.
     * A follower counts the hop done once the account stands on {@code landing}
     * ({@link openrsc.nav.walk.RouteKeeper}).
     *
     * @param fare item id to how many of it this hop consumes; empty for a free one
     * @param wears the items the crossing checks are worn, as {@link Transport#wornGate} picks them for {@code pack}
     * @param pack held on the way to the crossing, gate items worn; the next step holds it less {@code fare}
     */
    public record Hop(
            String transport, Point entry, Point landing, Map<Integer, Long> fare, Set<Integer> wears, Inventory pack)
            implements Step {

        public Hop {
            fare = Map.copyOf(fare);
            wears = Set.copyOf(wears);
        }
    }

    /** Bank at {@code bank}: arrive with {@code pack}, leave with {@code leaving}. */
    public record BankStop(Point bank, Inventory pack, Inventory leaving) implements Step {

        /**
         * The difference between {@code pack} and {@code leaving}: each item whose count rises is
         * withdrawn to its leaving count, each whose count falls is deposited to it, and each worn on
         * leaving but not on arriving is equipped.
         */
        public Manifest manifest() {
            Map<ItemId, Long> withdraw = new LinkedHashMap<>();
            Map<ItemId, Long> deposit = new LinkedHashMap<>();
            Map<ItemId, Long> notes = new LinkedHashMap<>();
            Set<ItemId> equip = new LinkedHashSet<>();
            Set<Integer> seen = new LinkedHashSet<>();
            for (InventorySlot s : leaving.slots()) {
                seen.add(s.item().id());
            }
            for (InventorySlot s : pack.slots()) {
                seen.add(s.item().id());
            }
            for (int id : seen) {
                long before = pack.count(NoteForm.UNNOTED, id);
                long after = leaving.count(NoteForm.UNNOTED, id);
                if (after > before) {
                    withdraw.put(ItemId.getById(id), after);
                } else if (after < before) {
                    deposit.put(ItemId.getById(id), after);
                }
                long notedAfter = leaving.count(NoteForm.NOTED, id);
                if (notedAfter != pack.count(NoteForm.NOTED, id)) {
                    notes.put(ItemId.getById(id), notedAfter);
                }
                if (leaving.isEquipped(id) && !pack.isEquipped(id)) {
                    equip.add(ItemId.getById(id));
                }
            }
            return new Manifest(withdraw, deposit, equip, notes);
        }
    }

    /**
     * Arrive in {@code target} holding {@code pack}, which the request's loadout accepts. For a
     * {@link openrsc.gamedata.geometry.Dest.AnyOf} request, {@code target} is the option chosen.
     */
    public record Arrive(Area target, Inventory pack) implements Step {}

    public Route {
        debits = Map.copyOf(debits);
    }
}
