package openrsc.nav.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.DelegatingInventory;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.TravelTime;
import openrsc.nav.TravelTimes;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.walk.Yield;

/**
 * Plans and walks routes without a logged-in bot: estimates, and walks simulated a tick at a time,
 * from any tile, for a given account or, where the account is {@code null}, for a probe account that
 * may use every transport.
 */
public final class NavProbe {

    /**
     * One step of a probed route. A walk step carries only its tile. A hop carries its transport's
     * name, cost and requirement text, and {@code wears}, what the crossing checks is worn. A bank
     * stop sets {@code bankVisit} and carries its manifest as absolute pack targets by item id, and
     * {@code because}, what the stop is for. {@code pack} is what is held once the step is done.
     */
    public record ProbeStep(
            int x,
            int y,
            String hopName,
            Integer hopCost,
            String requirements,
            boolean bankVisit,
            Map<Integer, Long> withdraw,
            Map<Integer, Long> deposit,
            Set<Integer> wears,
            List<String> because,
            Inventory pack) {}

    private NavProbe() {}

    /** {@code account} at {@code (x, y)}, or the {@link #allEligibleFacts} probe for {@code null}. */
    public static AccountFacts factsFor(AccountFacts account, Pathfinder pf, int x, int y) {
        return account == null ? allEligibleFacts(pf, x, y) : account.at(x, y);
    }

    /**
     * {@code policy}, or {@link NavPolicy#DEFAULT} when it is {@code null}; for a {@code null}
     * account, with every transport eligible.
     */
    public static NavPolicy policyFor(AccountFacts account, NavPolicy policy) {
        NavPolicy base = policy == null ? NavPolicy.DEFAULT : policy;
        return account == null ? base.withAllEligible() : base;
    }

    /**
     * A probe account at {@code (x, y)} with nothing: default stats, no items, no quests. Only
     * transports without requirements are open to it, so a route it cannot find may still exist for
     * a real account.
     */
    public static AccountFacts onFootFacts(ServerData defs, int x, int y) {
        return new AccountFacts.Snapshot(
                defs,
                Inventory.empty(),
                Bank.empty(),
                new MutableStats().snapshot(),
                new MutableQuests().snapshot(),
                Point.of(x, y));
    }

    private static AllEligible deriveAllEligible(Pathfinder pf) {
        MutableInventory inv = new MutableInventory();
        MutableBank bank = new MutableBank();
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.id, 99, 99, 0);
        }
        MutableQuests quests = new MutableQuests();
        Map<Integer, Long> carried = new LinkedHashMap<>();
        Set<Integer> worn = new LinkedHashSet<>();
        for (Transport spec : pf.transports()) {
            for (Fact f : spec.prereq().facts()) {
                switch (f.kind()) {
                    case CARRIED_AT_LEAST -> carried.merge(f.itemId(), (long) f.threshold(), Long::max);
                    case WEARING -> {
                        carried.merge(f.itemId(), 1L, Long::max);
                        worn.add(f.itemId());
                    }
                    case QUEST_AT_LEAST, QUEST_COMPLETE -> quests.setStage(f.itemId(), -1);
                    default -> {}
                }
            }
        }
        // Worn items get real, equipped slots, as many as a pack holds; everything else carried is
        // counted through the adjusting view alone:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L30
        int slot = 0;
        Set<Integer> slotted = new LinkedHashSet<>();
        for (int id : worn) {
            if (slot >= 30) {
                break;
            }
            inv.setSlot(
                    slot,
                    new InventorySlot(
                            slot, id, (int) Math.min(Integer.MAX_VALUE, carried.getOrDefault(id, 1L)), true, false));
            slotted.add(id);
            slot++;
        }
        bank.setSlot(0, new BankSlot(0, ItemId.COINS.id(), 1_000_000));
        Inventory base = inv.snapshot();
        Inventory probePack = new DelegatingInventory(pf.serverData()) {
            @Override
            protected Inventory delegate() {
                return base;
            }

            @Override
            protected long adjustment(int itemId) {
                if (slotted.contains(itemId)) {
                    return 0;
                }
                Long c = carried.get(itemId);
                return c == null ? 0 : c;
            }
        };
        return new AllEligible(base, probePack, bank.snapshot(), stats.snapshot(), quests.snapshot());
    }

    private record AllEligible(Inventory base, Inventory pack, Bank bank, Stats stats, Quests quests) {}

    private static final Map<Pathfinder, AllEligible> ALL_ELIGIBLE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * A probe account at {@code (x, y)} that meets nearly every transport's requirements: 99 in every
     * skill, every quest a requirement names complete, 1,000,000 coins banked, and every item a requirement counts
     * carried, worn where one asks for it worn. A gate on an exact quest stage stays closed.
     */
    public static AccountFacts allEligibleFacts(Pathfinder pf, int x, int y) {
        AllEligible profile = ALL_ELIGIBLE.computeIfAbsent(pf, NavProbe::deriveAllEligible);
        AccountFacts.Snapshot core = new AccountFacts.Snapshot(
                pf.serverData(), profile.base(), profile.bank(), profile.stats(), profile.quests(), Point.of(x, y));
        return core.withInventory(profile.pack());
    }

    /**
     * The engine's estimate from {@code (sx, sy)} to {@code (gx, gy)}. A {@code null} account probes as
     * {@link #allEligibleFacts}.
     */
    public static TravelTime estimateBetween(
            Pathfinder pf, int sx, int sy, int gx, int gy, AccountFacts account, NavPolicy policy) {
        return pf.nav()
                .estimate(
                        factsFor(account, pf, sx, sy), Point.of(sx, sy), Point.of(gx, gy), policyFor(account, policy));
    }

    /** {@link #estimateBetween} to each of {@code goals}, each {@code {x, y}}, in the same order. */
    public static TravelTimes estimateBatchBetween(
            Pathfinder pf, int sx, int sy, int[][] goals, AccountFacts account, NavPolicy policy) {
        List<Dest> dests = new ArrayList<>(goals.length);
        for (int[] g : goals) {
            dests.add(Point.of(g[0], g[1]));
        }
        return pf.nav()
                .estimateBatch(factsFor(account, pf, sx, sy), Point.of(sx, sy), dests, policyFor(account, policy));
    }

    private static final int MAX_TICKS = 6000;

    /**
     * Plans from {@code (sx, sy)} to {@code (gx, gy)} and walks it as a bot would: each simulated
     * tick re-plans from the tile stood on and takes one tile, a hop or a bank stop, which updates the
     * probe's pack and bank. Returns the steps walked, the start first, or {@code null} when there is
     * no route from the start. A walk that cannot finish, or lasts 6000 ticks, ends where it stopped.
     */
    public static List<ProbeStep> planBetween(
            Pathfinder pf, int sx, int sy, int gx, int gy, AccountFacts account, NavPolicy policy) {
        return planBetween(pf, sx, sy, gx, gy, account, policy, LoadoutSpec.any());
    }

    /** {@link #planBetween} for a walk meant to arrive holding {@code loadout}. */
    public static List<ProbeStep> planBetween(
            Pathfinder pf,
            int sx,
            int sy,
            int gx,
            int gy,
            AccountFacts account,
            NavPolicy policy,
            LoadoutSpec loadout) {
        return planBetween(pf, sx, sy, gx, gy, account, policy, loadout, new SimulationStats());
    }

    /** Where one {@link NavProbe#planBetween} simulation spent its time, which re-plans every tick. */
    public static final class SimulationStats {
        public int ticks;
        /** Full plans the engine made. */
        public int plans;
        /** Walks asked of the engine along a kept or fresh route. */
        public int walks;
        /** Time asking for routes, both kinds, in nanoseconds. */
        public long planNanos;
        /** Time aiming at each step and searching the tile path to it, in nanoseconds. */
        public long walkNanos;

        public long firstPlanNanos;
        /** What the first tick's plan built: {@code {routing metrics, leg planes}}. */
        public long[] firstPlanBuilt = new long[2];
        /** The route the engine planned from the start tile, or {@code null} when it had none. */
        public @Nullable Route enginePlan;
    }

    public static List<ProbeStep> planBetween(
            Pathfinder pf,
            int sx,
            int sy,
            int gx,
            int gy,
            AccountFacts account,
            NavPolicy policy,
            LoadoutSpec loadout,
            SimulationStats stats) {
        return planBetween(pf, sx, sy, gx, gy, account, policy, loadout, stats, null);
    }

    /** The NPCs a simulated walk meets. */
    public interface Crowd {
        /** The NPCs within {@code radius} tiles of {@code pos} at simulated tick {@code tick}. */
        List<? extends Yield.Sighting> around(int tick, Point pos, int radius);
    }

    public static List<ProbeStep> planBetween(
            Pathfinder pf,
            int sx,
            int sy,
            int gx,
            int gy,
            AccountFacts account,
            NavPolicy policy,
            LoadoutSpec loadout,
            SimulationStats stats,
            @Nullable Crowd crowd) {
        Point goal = Point.of(gx, gy);
        Point pos = Point.of(sx, sy);
        WalkSession walk = new WalkSession(pf, account, policy, loadout, pos, goal, stats);
        List<ProbeStep> out = new ArrayList<>();
        out.add(tile(pos, walk.pack()));
        for (int tick = 0; tick < MAX_TICKS && !pos.equals(goal); tick++) {
            List<? extends Yield.Sighting> npcs =
                    crowd == null ? List.of() : crowd.around(tick, pos, walk.viewRadius());
            WalkSession.Move move =
                    walk.tick(new WalkSession.View(pos, false, npcs)).move();
            switch (move) {
                case WalkSession.Stuck s -> {
                    return s.first() ? null : out;
                }
                case WalkSession.Arrived a -> {
                    return out;
                }
                case WalkSession.Hop h -> {
                    pos = h.landing();
                    out.add(h.step());
                }
                case WalkSession.Bank b -> out.add(b.step());
                case WalkSession.Walk w -> {
                    pos = w.chunk().get(1);
                    out.add(tile(pos, walk.pack()));
                }
                case WalkSession.BackOff b -> {
                    pos = b.tile();
                    out.add(tile(pos, walk.pack()));
                }
                case WalkSession.Hold hold -> out.add(tile(pos, walk.pack()));
                case WalkSession.Wait wait -> out.add(tile(pos, walk.pack()));
                case WalkSession.Attack attack -> out.add(tile(pos, walk.pack()));
                case WalkSession.Wear wear -> out.add(tile(pos, walk.pack()));
            }
        }
        return out;
    }

    static ProbeStep tile(Point p, Inventory pack) {
        return new ProbeStep(p.x(), p.y(), null, null, null, false, null, null, Set.of(), List.of(), pack);
    }

    /**
     * What a bank stop is for: {@code "loadout"} when it withdraws an item a loadout requirement names
     * or only deposits, then the name of each later hop whose fare, worn gate or bank-relaxed gate
     * item it withdraws. Inferred from what the stop moves, so it may be empty.
     */
    static List<String> reasons(Route route, Route.BankStop stop, LoadoutSpec loadout, Map<String, Transport> byName) {
        Set<Integer> withdrawn = new LinkedHashSet<>();
        stop.manifest().withdraw().keySet().forEach(id -> withdrawn.add(id.id()));
        if (withdrawn.isEmpty()) {
            return stop.manifest().deposit().isEmpty() ? List.of() : List.of("loadout");
        }
        List<String> out = new ArrayList<>(2);
        for (LoadoutSpec.Requirement req : loadout.requirements()) {
            if (claims(req, withdrawn)) {
                out.add("loadout");
                break;
            }
        }
        boolean past = false;
        for (Route.Step s : route.steps()) {
            if (s == stop) {
                past = true;
            } else if (past && s instanceof Route.Hop hop && supplies(hop, withdrawn, byName)) {
                out.add(hop.transport());
            }
        }
        return List.copyOf(out);
    }

    private static boolean claims(LoadoutSpec.Requirement req, Set<Integer> ids) {
        return switch (req) {
            case LoadoutSpec.Item item -> ids.contains(item.item().id());
            case LoadoutSpec.Pool pool -> pool.items().stream().anyMatch(m -> ids.contains(m.id()));
        };
    }

    private static boolean supplies(Route.Hop hop, Set<Integer> withdrawn, Map<String, Transport> byName) {
        for (int id : hop.fare().keySet()) {
            if (withdrawn.contains(id)) {
                return true;
            }
        }
        for (int id : hop.wears()) {
            if (withdrawn.contains(id)) {
                return true;
            }
        }
        Transport t = byName.get(hop.transport());
        if (t != null) {
            for (Fact f : t.prereq().facts()) {
                if (f.bankRelaxed() && !f.bansItems() && withdrawn.contains(f.itemId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A stop's identity for spotting one that repeats: its bank tile and everything it moves. */
    static String stopKey(Route.BankStop stop) {
        var m = stop.manifest();
        return stop.bank() + "|" + m.withdraw() + "|" + m.deposit() + "|" + m.equip();
    }

    static AccountFacts holding(AccountFacts facts, Inventory pack) {
        return holding(facts, pack, facts.bank());
    }

    /**
     * {@code facts} holding {@code pack} and {@code bank}, as a fresh snapshot: the planner reads the
     * account's holdings, which a view over the old snapshot would still answer.
     */
    static AccountFacts holding(AccountFacts facts, Inventory pack, Bank bank) {
        // Frozen where the account's pack was, since the planner keys a trip only on frozen contents;
        // the all-eligible pack is a counting view that freezing would empty.
        Inventory held = pack instanceof Inventory.Frozen || !(facts.inventory() instanceof Inventory.Frozen)
                ? pack
                : Inventory.of(List.copyOf(pack.slots()), pack.wornOffPack());
        return new AccountFacts.Snapshot(facts.serverData(), held, bank, facts.stats(), facts.quests(), facts.pos());
    }

    /** {@code facts} after {@code stop}: holding what it leaves with, its bank changed by what moved. */
    static AccountFacts settled(AccountFacts facts, Route.BankStop stop) {
        Inventory before = facts.inventory();
        Inventory after = stop.leaving();
        Map<Integer, Long> banked = new LinkedHashMap<>();
        for (BankSlot slot : facts.bank().slots()) {
            banked.merge(slot.item().id(), (long) slot.amount(), Long::sum);
        }
        Set<Integer> moved = new LinkedHashSet<>();
        before.slots().forEach(slot -> moved.add(slot.item().id()));
        after.slots().forEach(slot -> moved.add(slot.item().id()));
        for (int id : moved) {
            banked.merge(id, before.count(NoteForm.BOTH, id) - after.count(NoteForm.BOTH, id), Long::sum);
        }
        List<BankSlot> slots = new ArrayList<>();
        banked.forEach((id, n) -> {
            if (n > 0) {
                slots.add(new BankSlot(slots.size(), id, (int) Math.min(Integer.MAX_VALUE, n)));
            }
        });
        return holding(facts, after, Bank.of(slots));
    }
}
