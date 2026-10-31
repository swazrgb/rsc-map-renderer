package openrsc.nav.diagnose;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.quest.Quests;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemRef;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.Route;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.RouteContext;
import org.jetbrains.annotations.Nullable;

/**
 * Explains a failed plan by asking the planner again with one kind of constraint lifted at a time,
 * and reporting which lift made a route appear. Plans are deterministic, so a lift that routes shows
 * the lifted thing was in the way.
 *
 * <p>The rungs are asked in the order of {@link Diagnosis.Kind}. The loadout, shed and rules rungs
 * list every minimal fix, stopping at a budget of fixes and of plans: a diagnosis can cost a few
 * hundred full plans of the trip. Stateless; thread-safe as far as the {@link Planner} it is given
 * is.
 */
public final class NoRouteReport {

    private static final int MAX_PROBES = 24;

    static final int MAX_FIXES = 12;

    static final int MAX_PLANS = 300;

    private static final int MAX_HOPS = 6;

    private static final Quests ALL_QUESTS_DONE = allQuestsDone();

    private NoRouteReport() {}

    private static Quests allQuestsDone() {
        MutableQuests done = new MutableQuests();
        for (QuestId q : QuestId.values()) {
            done.setStage(q.id(), QuestId.STAGE_COMPLETED);
        }
        done.setQuestPoints(1000);
        return done.snapshot();
    }

    private record Ask(Planner planner, AccountFacts account, ServerData env, Point from, Dest goal, int[] plans) {

        @Nullable
        Route route(LoadoutSpec loadout, NavPolicy policy) {
            plans[0]++;
            return planner.plan(new PlanRequest(account, from, goal, loadout, policy))
                            instanceof PlanResult.Planned(Route r)
                    ? r
                    : null;
        }

        boolean routes(LoadoutSpec loadout, NavPolicy policy) {
            return route(loadout, policy) != null;
        }
    }

    /**
     * The diagnosis of a trip the planner returned {@code NoRoute} for. {@code account}, {@code from},
     * {@code goal}, {@code loadout} and {@code policy} are those of the plan that failed, since every
     * probe re-plans them with one thing changed. A probe that throws a {@code RuntimeException}
     * gives an {@link Diagnosis.Kind#UNDIAGNOSED} verdict naming it rather than propagating.
     *
     * @param questsKnown false when the account's quest stages have not been read yet, so every quest
     *     reads as not started
     */
    public static Diagnosis explain(
            Planner planner,
            AccountFacts account,
            CollisionMap map,
            Point from,
            Dest goal,
            LoadoutSpec loadout,
            NavPolicy policy,
            boolean questsKnown) {
        try {
            Point at = representative(goal);
            if (at != null && map.inBounds(at.x(), at.y()) && map.fullyBlocked(at.x(), at.y())) {
                return one(
                        Diagnosis.Kind.DESTINATION, "nothing can stand on the destination tile", List.of(), List.of());
            }
            Ask ask = new Ask(planner, account, account.serverData(), from, goal, new int[1]);
            if (!questsKnown && routesWithQuestsDone(ask, loadout, policy)) {
                return one(
                        Diagnosis.Kind.QUESTS_UNKNOWN,
                        "the login quest table has not arrived yet, so every quest read as not started —"
                                + " the same trip routes once the stages are known",
                        List.of(),
                        List.of());
            }
            return classify(ask, map, loadout, policy);
        } catch (RuntimeException e) {
            return one(Diagnosis.Kind.UNDIAGNOSED, "the probe threw " + e, List.of(), List.of());
        }
    }

    private static Diagnosis one(
            Diagnosis.Kind kind, String summary, List<Diagnosis.Blocker> blockers, List<String> hops) {
        return new Diagnosis(
                kind,
                summary,
                blockers.isEmpty() && hops.isEmpty()
                        ? List.of()
                        : List.of(new Diagnosis.Fix(kind, summary, blockers, hops)),
                true);
    }

    private static boolean routesWithQuestsDone(Ask ask, LoadoutSpec loadout, NavPolicy policy) {
        AccountFacts done = ask.account().withQuests(ALL_QUESTS_DONE);
        return ask.planner().plan(new PlanRequest(done, ask.from(), ask.goal(), loadout, policy))
                instanceof PlanResult.Planned;
    }

    private static Diagnosis classify(Ask ask, CollisionMap map, LoadoutSpec loadout, NavPolicy policy) {
        // Every rung below reads a lift that routes as proof, which an ask that already routes would
        // give for any lift.
        if (ask.routes(loadout, policy)) {
            return one(
                    Diagnosis.Kind.UNDIAGNOSED,
                    "the engine plans this trip as asked — whatever failed is downstream of the route",
                    List.of(),
                    List.of());
        }

        if (ask.routes(LoadoutSpec.any(), policy)) {
            return ask.routes(respec(loadout, loadout.requirements(), true), policy)
                    ? shed(ask, loadout, policy)
                    : bands(ask, loadout, policy);
        }

        Route emptied = ask.route(LoadoutSpec.empty(), policy);
        if (emptied != null) {
            return refused(ask, policy, emptied);
        }

        Diagnosis rules = rules(ask, policy);
        if (rules != null) {
            return rules;
        }

        return geography(map, ask.from(), ask.goal());
    }

    private static Diagnosis bands(Ask ask, LoadoutSpec loadout, NavPolicy policy) {
        List<LoadoutSpec.Requirement> all = loadout.requirements();
        Search<LoadoutSpec.Requirement> search = everyFix(ask.plans(), forbidden -> {
            Function<List<LoadoutSpec.Requirement>, Route> dropping =
                    d -> ask.route(respec(loadout, without(all, d), true), policy);
            List<LoadoutSpec.Requirement> droppable = without(all, forbidden);
            Route w = dropping.apply(droppable);
            return w == null ? null : narrowed(droppable, w, dropping);
        });
        List<Found<LoadoutSpec.Requirement>> found = new ArrayList<>(search.found());
        if (found.isEmpty()) {
            return one(
                    Diagnosis.Kind.LOADOUT,
                    "the trip cannot be made to satisfy these arrival bands, even with every one dropped",
                    List.of(),
                    List.of());
        }
        found.sort(Comparator.<Found<LoadoutSpec.Requirement>>comparingInt(
                        f -> f.changes().size())
                .thenComparingInt(f -> f.witness().totalCostDeci()));
        List<Diagnosis.Fix> fixes = new ArrayList<>(found.size());
        for (Found<LoadoutSpec.Requirement> f : found) {
            List<Diagnosis.Blocker> blockers = new ArrayList<>(f.changes().size());
            for (LoadoutSpec.Requirement r : f.changes()) {
                List<Integer> ids = ids(r);
                String what = band(ask.env(), r) + " (" + stock(ask.account(), ids) + ")";
                List<LoadoutSpec.Requirement> others = new ArrayList<>(f.changes());
                others.remove(r);
                // The rest of the fix dropped: alone, a band whose fix has other members never routes.
                String why = why(ask, respec(loadout, without(all, others), true), r, policy);
                blockers.add(Diagnosis.Blocker.ofItems(what, why, fixFor(ask, r), ids, null));
            }
            String summary = blockers.size() == 1
                    ? "the trip cannot be made to satisfy this arrival band"
                    : "the trip plans only once every one of these " + blockers.size()
                            + " arrival bands gives — each blocks it for a reason of its own";
            fixes.add(new Diagnosis.Fix(
                    Diagnosis.Kind.LOADOUT,
                    summary,
                    blockers,
                    hopNames(f.witness().steps())));
        }
        return verdict(fixes, search.complete());
    }

    private static <T> List<T> without(List<T> all, List<T> gone) {
        List<T> out = new ArrayList<>(all.size());
        for (T t : all) {
            if (!gone.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String why(Ask ask, LoadoutSpec loadout, LoadoutSpec.Requirement blocker, NavPolicy policy) {
        if (!(blocker instanceof LoadoutSpec.Item item) || item.min() <= 0) {
            return surplus(ask.env(), blocker);
        }
        String name = name(ask.env(), item.item().id());
        List<LoadoutSpec.Requirement> floorless = new ArrayList<>(loadout.requirements());
        floorless.replaceAll(r -> r == blocker ? item.withBand(0, item.target(), item.max()) : r);
        Route free = ask.route(respec(loadout, floorless, true), policy);
        if (free == null) {
            return surplus(ask.env(), blocker);
        }
        long fare = fare(free, item.item().id());
        long carried =
                ask.account().inventory().count(NoteForm.BOTH, item.item().id());
        if (fare > 0 && carried - fare < item.min()) {
            String at = spender(free, item.item().id());
            return (at == null ? "the route" : at) + " spends " + fare + " " + name
                    + ", leaving fewer than the loadout's " + item.min();
        }
        long held = carried + ask.account().bank().count(item.item().id());
        return held < item.min()
                ? "the loadout wants " + item.min() + " " + name + ", but only " + held + " exist"
                : "the loadout wants " + item.min() + " " + name + ", and no route keeps that many";
    }

    private static String surplus(ServerData env, LoadoutSpec.Requirement r) {
        if (r instanceof LoadoutSpec.Item it) {
            return "the route needs more " + name(env, it.item().id()) + " than the loadout's max of " + num(it.max());
        }
        if (r instanceof LoadoutSpec.Pool pool) {
            String tiers = band(env, pool);
            return "the route needs more " + tiers.substring(0, tiers.lastIndexOf(' ')) + " than the loadout's max of "
                    + num(pool.max());
        }
        return "the loadout's " + band(env, r) + " blocks every route";
    }

    private static String fixFor(Ask ask, LoadoutSpec.Requirement r) {
        if (r instanceof LoadoutSpec.Item item && item.min() > 0) {
            long held =
                    ask.account().inventory().count(NoteForm.BOTH, item.item().id())
                            + ask.account().bank().count(item.item().id());
            return "lower this row's arrive count below " + item.min()
                    + (held < item.min()
                            ? ", or stock more of it — only " + held + " exists between the" + " pack and the bank"
                            : ", or drop the row");
        }
        return "drop this row, or raise its arrive count so the trip may keep what it needs";
    }

    private static Diagnosis shed(Ask ask, LoadoutSpec loadout, NavPolicy policy) {
        Route witness = ask.route(respec(loadout, loadout.requirements(), true), policy);
        if (witness == null) {
            return one(
                    Diagnosis.Kind.SHED,
                    "the loadout banks what it does not name, and no arrangement of stops does it",
                    List.of(),
                    List.of());
        }
        Map<Integer, Long> unclaimed = new LinkedHashMap<>();
        carried(ask.account()).forEach((id, n) -> {
            if (!loadout.claims(id)) {
                unclaimed.put(id, n);
            }
        });
        // What the route withdraws on the way is ordered banked again just like what is carried.
        for (Route.Step s : witness.steps()) {
            for (InventorySlot slot : s.pack().slots()) {
                int id = slot.item().id();
                if (!loadout.claims(id)) {
                    unclaimed.merge(id, s.pack().count(NoteForm.BOTH, id), Math::max);
                }
            }
        }
        List<Integer> ids = new ArrayList<>(unclaimed.keySet());
        Search<Integer> search = everyFix(ask.plans(), forbidden -> {
            Function<List<Integer>, Route> keepingOnly = k -> ask.route(keeping(loadout, k, unclaimed), policy);
            List<Integer> kept = without(ids, forbidden);
            Route w = keepingOnly.apply(kept);
            return w == null ? null : narrowed(kept, w, keepingOnly);
        });
        List<Found<Integer>> found = new ArrayList<>(search.found());
        if (found.isEmpty()) {
            return one(
                    Diagnosis.Kind.SHED,
                    "the loadout banks what it does not name, and keeping all of it does not" + " route either",
                    List.of(),
                    hopNames(witness.steps()));
        }
        found.sort(Comparator.<Found<Integer>>comparingInt(f -> f.changes().size())
                .thenComparingInt(f -> f.witness().totalCostDeci()));
        List<Diagnosis.Fix> fixes = new ArrayList<>(found.size());
        for (Found<Integer> f : found) {
            Route route = f.witness();
            boolean stops = route.steps().stream().anyMatch(Route.BankStop.class::isInstance);
            List<Diagnosis.Blocker> blockers = new ArrayList<>(f.changes().size());
            List<String> sentences = new ArrayList<>(f.changes().size());
            for (int id : f.changes()) {
                Diagnosis.Blocker b = shedBlocker(ask, route, policy, id, unclaimed.get(id), stops);
                blockers.add(b);
                sentences.add(b.why());
            }
            fixes.add(new Diagnosis.Fix(
                    Diagnosis.Kind.SHED, String.join("; ", sentences), blockers, hopNames(route.steps())));
        }
        return verdict(fixes, search.complete());
    }

    private static Diagnosis.Blocker shedBlocker(
            Ask ask, Route witness, NavPolicy policy, int id, long held, boolean stops) {
        String item = name(ask.env(), id);
        long inPack = ask.account().inventory().count(NoteForm.BOTH, id);
        String what = item + " ×" + held + (inPack > 0 ? " in the pack" : ", picked up on the way");
        long spent = fare(witness, id);
        if (spent > 0) {
            String at = spender(witness, id);
            return Diagnosis.Blocker.ofItems(
                    what,
                    (at == null ? "the route" : at) + " spends " + spent + " " + item + ", but the loadout wants none",
                    "give this row an arrive count of " + spent + " — it is spent on the way, so you will"
                            + " not arrive holding it",
                    List.of(id),
                    at);
        }
        String gate = credentialFor(ask, witness, id, policy);
        if (gate != null) {
            return Diagnosis.Blocker.ofItems(
                    what,
                    gate + " needs a " + item + ", but the loadout wants none",
                    "give this row an arrive count of " + held + " so the trip may keep it"
                            + " (or tick \"keep everything else\")",
                    List.of(id),
                    gate);
        }
        return Diagnosis.Blocker.ofItems(
                what,
                stops
                        ? "the loadout wants no " + item + ", but banking it closes "
                                + hops(afterFirstStop(witness.steps()))
                        : "the loadout wants no " + item + ", but the route passes no bank to leave it in",
                "give this row an arrive count of " + held + " so the trip may keep it"
                        + " (or tick \"keep everything else\")",
                List.of(id),
                null);
    }

    private static @Nullable String spender(Route route, int itemId) {
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.Hop hop && hop.fare().getOrDefault(itemId, 0L) > 0) {
                return hop.transport();
            }
        }
        return null;
    }

    /**
     * The last hop on {@code route} whose gate passes with the hop's pack and fails without
     * {@code item}. Tested rather than read off {@link Route.Hop#wears()}, which names worn
     * credentials only, not carried ones such as a rope or a knife.
     */
    private static @Nullable String credentialFor(Ask ask, Route route, int item, NavPolicy policy) {
        String gate = null;
        for (Route.Step s : route.steps()) {
            if (!(s instanceof Route.Hop hop)) {
                continue;
            }
            Prereq prereq = ask.planner().gateOf(hop.transport());
            if (prereq == null) {
                continue;
            }
            RouteContext with = context(ask, hop.pack(), hop.entry(), policy);
            RouteContext without = context(ask, less(hop.pack(), item), hop.entry(), policy);
            if (prereq.test(with) && !prereq.test(without)) {
                gate = hop.transport();
            }
        }
        return gate;
    }

    private static RouteContext context(Ask ask, Inventory pack, Point at, NavPolicy policy) {
        AccountFacts facts = ask.account().at(at.x(), at.y());
        return new RouteContext(facts.withInventory(pack), policy, ask.env());
    }

    private static Inventory less(Inventory pack, int item) {
        List<InventorySlot> kept = new ArrayList<>(pack.slots().size());
        for (InventorySlot s : pack.slots()) {
            if (s.item().id() != item) {
                kept.add(s);
            }
        }
        return Inventory.of(kept, pack.wornOffPack());
    }

    private static LoadoutSpec keeping(LoadoutSpec loadout, List<Integer> ids, Map<Integer, Long> held) {
        List<LoadoutSpec.Requirement> bands = new ArrayList<>(loadout.requirements());
        for (int id : ids) {
            long n = held.get(id);
            // Target as well as max: a bank stop settles a band down to its target.
            bands.add(new LoadoutSpec.Item(refOf(id), 0, n, n, false));
        }
        return respec(loadout, bands, loadout.anything());
    }

    private static Diagnosis refused(Ask ask, NavPolicy policy, Route opened) {
        List<Diagnosis.Blocker> blockers = new ArrayList<>();
        int budget = MAX_PROBES;
        for (Map.Entry<Integer, Long> e : carried(ask.account()).entrySet()) {
            if (budget-- == 0) {
                break;
            }
            int id = e.getKey();
            long n = e.getValue();
            LoadoutSpec keepOne = new LoadoutSpec(List.of(new LoadoutSpec.Item(refOf(id), 0, n, n, false)));
            if (!ask.routes(keepOne, policy)) {
                blockers.add(Diagnosis.Blocker.ofItems(
                        name(ask.env(), id) + " ×" + n + " in the pack",
                        "the only way in refuses a " + name(ask.env(), id) + " in the pack",
                        "bank it before the trip, or state an arrival that lets a stop shed it",
                        List.of(id),
                        null));
            }
        }
        String summary = blockers.isEmpty()
                ? "the pack as a whole is refused on the only way in, though no single item is"
                : "the only way in refuses something in the pack";
        return one(Diagnosis.Kind.PACK, summary, blockers, hopNames(opened.steps()));
    }

    private interface Lift {}

    /**
     * Every hop behind one gate ({@link Planner#sameGate}), lifted together: lifted one hop at a time,
     * hundreds of hops behind one gate would make every path through them a fix of its own.
     */
    private record Gate(List<String> hops) implements Lift {}

    private record Prohibit(String key) implements Lift {}

    private record Teleports(boolean spells) implements Lift {}

    private static List<Lift> policyLifts(NavPolicy policy) {
        List<Lift> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : policy.costOverrides().entrySet()) {
            if (e.getValue() == NavPolicy.PROHIBITIVE) {
                out.add(new Prohibit(e.getKey()));
            }
        }
        if (!policy.teleportSpells()) {
            out.add(new Teleports(true));
        }
        if (!policy.teleportJewellery()) {
            out.add(new Teleports(false));
        }
        return out;
    }

    private static NavPolicy widened(NavPolicy policy, List<Lift> lifts) {
        Map<String, Integer> overrides = new HashMap<>(policy.costOverrides());
        boolean spells = policy.teleportSpells();
        boolean jewellery = policy.teleportJewellery();
        for (Lift l : lifts) {
            if (l instanceof Prohibit p) {
                overrides.remove(p.key());
            } else if (l instanceof Teleports t) {
                spells |= t.spells();
                jewellery |= !t.spells();
            }
        }
        NavPolicy out = new NavPolicy(
                policy.danger(),
                spells,
                jewellery,
                Map.copyOf(overrides),
                policy.assumeAllEligible(),
                policy.avoidSkulled(),
                policy.berth());
        // After the knobs: lift() leaves a prohibited transport prohibited, so a gate opens only
        // where the widened policy already allows its hop.
        for (Lift l : lifts) {
            if (l instanceof Gate g) {
                for (String hop : g.hops()) {
                    out = out.lift(() -> hop);
                }
            }
        }
        return out;
    }

    /**
     * One fix: its changes and the route they open, or a {@code null} witness when these are all the
     * changes a search started from and together they still do not route.
     */
    record Found<T>(List<T> changes, @Nullable Route witness) {}

    /** One minimal fix that uses none of {@code forbidden}, or {@code null} when there is none. */
    interface Minimal<T> {
        @Nullable
        Found<T> avoiding(List<T> forbidden);
    }

    /**
     * Every minimal fix {@code minimal} admits, by Reiter's hitting-set tree: a fix other than one
     * already found must leave out one of its changes, so each found fix is branched on once per
     * change, with that change forbidden. Stops at {@link #MAX_FIXES} fixes, or once
     * {@link #MAX_PLANS} plans counted in {@code plans[0]} have been spent, with {@code complete}
     * false. A root fix with a {@code null} witness is returned alone.
     */
    static <T> Search<T> everyFix(int[] plans, Minimal<T> minimal) {
        List<Found<T>> found = new ArrayList<>();
        Set<Set<T>> seen = new HashSet<>();
        ArrayDeque<List<T>> todo = new ArrayDeque<>();
        todo.add(List.of());
        int spent = plans[0];
        while (!todo.isEmpty()) {
            if (found.size() >= MAX_FIXES || plans[0] - spent >= MAX_PLANS) {
                return new Search<>(found, false);
            }
            List<T> forbidden = todo.poll();
            if (!seen.add(new HashSet<>(forbidden))) {
                continue;
            }
            Found<T> f = null;
            for (Found<T> g : found) {
                if (disjoint(g.changes(), forbidden)) {
                    f = g;
                    break;
                }
            }
            if (f == null) {
                f = minimal.avoiding(forbidden);
                if (f == null) {
                    continue;
                }
                if (f.witness() == null) {
                    if (forbidden.isEmpty()) {
                        return new Search<>(List.of(f), true);
                    }
                    continue;
                }
                found.add(f);
            }
            for (T c : f.changes()) {
                List<T> child = new ArrayList<>(forbidden);
                child.add(c);
                todo.add(child);
            }
        }
        return new Search<>(found, true);
    }

    record Search<T>(List<Found<T>> found, boolean complete) {}

    /**
     * {@code changes} less every one the trip still routes without, so each survivor is necessary,
     * with the route of the last probe that routed.
     */
    private static <T> Found<T> narrowed(List<T> changes, Route witness, Function<List<T>, Route> routeWith) {
        List<T> keep = new ArrayList<>(changes);
        int i = 0;
        while (i < keep.size()) {
            T dropped = keep.remove(i);
            Route r = routeWith.apply(keep);
            if (r == null) {
                keep.add(i, dropped);
                i++;
            } else {
                witness = r;
            }
        }
        return new Found<>(List.copyOf(keep), witness);
    }

    private static Diagnosis verdict(List<Diagnosis.Fix> fixes, boolean complete) {
        String summary = fixes.size() == 1 && complete
                ? fixes.get(0).summary()
                : "any one of these " + fixes.size() + " fixes opens the trip"
                        + (complete ? "" : " — the search stopped there, and there may be more");
        return new Diagnosis(fixes.get(0).kind(), summary, fixes, complete);
    }

    private static @Nullable Diagnosis rules(Ask ask, NavPolicy policy) {
        List<Lift> knobs = policyLifts(policy);
        Search<Lift> search = everyFix(ask.plans(), forbidden -> minimal(ask, policy, knobs, forbidden));
        List<Found<Lift>> found = new ArrayList<>(search.found());
        if (found.isEmpty()) {
            return null;
        }
        if (found.get(0).witness() == null) {
            return unproven(ask, policy, found.get(0).changes());
        }
        // Policy-only fixes first: they ask nothing of the account.
        found.sort(Comparator.<Found<Lift>>comparingInt(f -> gates(f.changes()) == 0
                        ? 0
                        : gates(f.changes()) == f.changes().size() ? 1 : 2)
                .thenComparingInt(f -> f.changes().size())
                .thenComparingInt(f -> f.witness().totalCostDeci()));
        List<Diagnosis.Fix> fixes = new ArrayList<>(found.size());
        for (Found<Lift> f : found) {
            RouteContext ctx = new RouteContext(
                    ask.account().at(ask.from().x(), ask.from().y()), widened(policy, knobsOf(f.changes())), ask.env());
            fixes.add(new Diagnosis.Fix(
                    kindOf(f.changes()),
                    fixSummary(f.changes()),
                    blockers(ask, ctx, f.changes(), hopNames(f.witness().steps())),
                    hopNames(f.witness().steps())));
        }
        return verdict(fixes, search.complete());
    }

    private static @Nullable Found<Lift> minimal(Ask ask, NavPolicy policy, List<Lift> knobs, List<Lift> forbidden) {
        List<Lift> keep = new ArrayList<>();
        for (Lift k : knobs) {
            if (!forbidden.contains(k)) {
                keep.add(k);
            }
        }
        Route opened = ask.route(LoadoutSpec.any(), fenced(widened(policy, keep).withAllEligible(), forbidden));
        if (opened == null) {
            return null;
        }
        // Gates read under the lifted knobs, so a teleport the policy turned off is blamed on the
        // policy and not on the account as well.
        RouteContext ctx =
                new RouteContext(ask.account().at(ask.from().x(), ask.from().y()), widened(policy, keep), ask.env());
        for (String hop : hopNames(opened.steps())) {
            Prereq prereq = ask.planner().gateOf(hop);
            Gate gate = new Gate(ask.planner().sameGate(hop));
            if (prereq != null && !prereq.test(ctx) && !keep.contains(gate)) {
                keep.add(gate);
            }
        }
        Function<List<Lift>, Route> lifting = k -> ask.route(LoadoutSpec.any(), fenced(widened(policy, k), forbidden));
        Route witness = lifting.apply(keep);
        return witness == null ? new Found<>(List.copyOf(keep), null) : narrowed(keep, witness, lifting);
    }

    private static Diagnosis unproven(Ask ask, NavPolicy policy, List<Lift> lifts) {
        RouteContext ctx = new RouteContext(
                ask.account().at(ask.from().x(), ask.from().y()), widened(policy, knobsOf(lifts)), ask.env());
        List<String> hops = new ArrayList<>();
        for (Lift l : lifts) {
            if (l instanceof Gate g) {
                hops.add(g.hops().get(0));
            }
        }
        return one(
                kindOf(lifts),
                "the only route takes hops closed to this account, and passing their"
                        + " gates alone does not route it",
                blockers(ask, ctx, lifts, hops),
                hops);
    }

    private static NavPolicy fenced(NavPolicy policy, List<Lift> forbidden) {
        for (Lift l : forbidden) {
            if (l instanceof Gate g) {
                for (String hop : g.hops()) {
                    policy = policy.prohibit(() -> hop);
                }
            }
        }
        return policy;
    }

    private static <T> boolean disjoint(List<T> a, List<T> b) {
        for (T l : a) {
            if (b.contains(l)) {
                return false;
            }
        }
        return true;
    }

    private static int gates(List<Lift> lifts) {
        int n = 0;
        for (Lift l : lifts) {
            if (l instanceof Gate) {
                n++;
            }
        }
        return n;
    }

    private static List<Lift> knobsOf(List<Lift> lifts) {
        List<Lift> out = new ArrayList<>();
        for (Lift l : lifts) {
            if (!(l instanceof Gate)) {
                out.add(l);
            }
        }
        return out;
    }

    private static String fixSummary(List<Lift> lifts) {
        int gates = gates(lifts);
        if (gates == 0) {
            return "the account can make this trip — the policy shuts every way it could take";
        }
        if (gates == lifts.size()) {
            return gates == 1
                    ? "a hop the trip needs is closed to this account"
                    : "these " + gates + " hops are closed to this account, and the trip needs every one of" + " them";
        }
        return "the policy shuts this way, and it also needs a gate this account fails";
    }

    private static Diagnosis.Kind kindOf(List<Lift> lifts) {
        return gates(lifts) == lifts.size() ? Diagnosis.Kind.ELIGIBILITY : Diagnosis.Kind.POLICY;
    }

    private static List<Diagnosis.Blocker> blockers(Ask ask, RouteContext ctx, List<Lift> lifts, List<String> route) {
        List<Diagnosis.Blocker> out = new ArrayList<>(lifts.size());
        for (Lift l : lifts) {
            if (l instanceof Gate g) {
                String hop = g.hops().get(0);
                for (String h : route) {
                    if (g.hops().contains(h)) {
                        hop = h;
                        break;
                    }
                }
                int more = g.hops().size() - 1;
                Prereq prereq = ask.planner().gateOf(hop);
                out.add(new Diagnosis.Blocker(
                        "the hop \"" + hop + "\"" + (more == 0 ? "" : " (and " + more + " more behind the same gate)"),
                        hop + " needs " + unmet(prereq, ctx),
                        "satisfy " + unmet(prereq, ctx),
                        Unmet.items(prereq, ctx),
                        Unmet.quests(prereq, ctx),
                        Unmet.skills(prereq, ctx),
                        Unmet.cache(prereq, ctx),
                        hop));
            } else if (l instanceof Prohibit p) {
                out.add(Diagnosis.Blocker.ofItems(
                        "the policy's prohibit on \"" + p.key() + "\"",
                        "every way there crosses " + p.key() + ", which the policy prohibits",
                        "drop the prohibit on " + p.key(),
                        List.of(),
                        p.key()));
            } else if (l instanceof Teleports t) {
                String family = t.spells() ? "teleport spells" : "teleport jewellery";
                out.add(Diagnosis.Blocker.ofItems(
                        family + " off in the policy",
                        "every way there takes " + family + ", which the policy turns off",
                        "allow " + family,
                        List.of(),
                        null));
            }
        }
        return out;
    }

    private static String unmet(Prereq prereq, RouteContext ctx) {
        ItemDefs defs = ctx.serverData().itemDefs();
        // Not a StringJoiner: nav-web compiles this class with TeaVM, whose classlib has none.
        StringBuilder out = new StringBuilder();
        for (Prereq part : prereq.namedParts()) {
            if (!part.test(ctx)) {
                or(out, part.display(defs));
            }
        }
        if (out.length() > 0) {
            return out.toString();
        }
        for (Fact f : prereq.facts()) {
            if (!f.test(ctx)) {
                or(out, f.display(defs));
            }
        }
        return out.length() > 0 ? out.toString() : prereq.display(defs);
    }

    private static final class Unmet {

        static List<Integer> items(Prereq prereq, RouteContext ctx) {
            List<Integer> out = new ArrayList<>();
            for (Fact f : failing(prereq, ctx)) {
                if ((f.kind() == Fact.Kind.CARRIED_AT_LEAST || f.kind() == Fact.Kind.WEARING)
                        && !out.contains(f.itemId())) {
                    out.add(f.itemId());
                }
            }
            return out;
        }

        static List<Integer> quests(Prereq prereq, RouteContext ctx) {
            List<Integer> out = new ArrayList<>();
            for (Fact f : failing(prereq, ctx)) {
                boolean quest =
                        switch (f.kind()) {
                            case QUEST_AT_LEAST, QUEST_COMPLETE, QUEST_EQUALS -> true;
                            default -> false;
                        };
                if (quest && !out.contains(f.itemId())) {
                    out.add(f.itemId());
                }
            }
            return out;
        }

        static List<String> skills(Prereq prereq, RouteContext ctx) {
            List<String> out = new ArrayList<>();
            for (Fact f : failing(prereq, ctx)) {
                if ((f.kind() == Fact.Kind.SKILL_AT_LEAST || f.kind() == Fact.Kind.BASE_SKILL_AT_LEAST)
                        && !out.contains(f.skill().name())) {
                    out.add(f.skill().name());
                }
            }
            return out;
        }

        static List<String> cache(Prereq prereq, RouteContext ctx) {
            List<String> out = new ArrayList<>();
            for (Fact f : failing(prereq, ctx)) {
                boolean cache = f.kind() == Fact.Kind.CACHE_BOOL
                        || f.kind() == Fact.Kind.CACHE_INT_AT_LEAST
                        || f.kind() == Fact.Kind.CACHE_INT_EQUALS;
                if (cache && !out.contains(f.cacheKey())) {
                    out.add(f.cacheKey());
                }
            }
            return out;
        }

        private static List<Fact> failing(Prereq prereq, RouteContext ctx) {
            List<Fact> out = new ArrayList<>();
            for (Fact f : prereq.facts()) {
                if (!f.test(ctx)) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    private static void or(StringBuilder out, String part) {
        if (out.length() > 0) {
            out.append(", or ");
        }
        out.append(part);
    }

    private static Diagnosis geography(CollisionMap map, Point from, Dest goal) {
        Point g = representative(goal);
        int a = componentNear(map, from.x(), from.y());
        int b = g == null ? -1 : componentNear(map, g.x(), g.y());
        String why = a == b && a != -1
                ? "both ends are in walk component " + a + " — a plain walk exists, so what is missing is"
                        + " in the transport graph, not the map"
                : "walk component " + a + " → " + b + ": no transport joins them for any account";
        return one(
                Diagnosis.Kind.GEOGRAPHY, "nothing reaches that tile, for any account — " + why, List.of(), List.of());
    }

    /**
     * The walk component of the nearest standable tile within 3 tiles, or {@code -1}: a goal's centre
     * can be blocked scenery.
     */
    private static int componentNear(CollisionMap map, int x, int y) {
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (map.inBounds(x + dx, y + dy) && !map.fullyBlocked(x + dx, y + dy)) {
                        return map.componentId(x + dx, y + dy);
                    }
                }
            }
        }
        return -1;
    }

    private static long fare(Route route, int itemId) {
        long n = 0;
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.Hop hop) {
                n += hop.fare().getOrDefault(itemId, 0L);
            }
        }
        return n;
    }

    private static String band(ServerData env, LoadoutSpec.Requirement r) {
        if (r instanceof LoadoutSpec.Item it) {
            return name(env, it.item().id()) + " " + numbers(it.min(), it.target(), it.max())
                    + (it.worn() ? " worn" : "");
        }
        if (r instanceof LoadoutSpec.Pool pool) {
            StringBuilder tiers = new StringBuilder();
            for (ItemRef m : pool.items()) {
                if (tiers.length() > 0) {
                    tiers.append(" or ");
                }
                tiers.append(name(env, m.id()));
            }
            return tiers + " " + numbers(pool.min(), pool.target(), pool.max());
        }
        return String.valueOf(r);
    }

    private static String numbers(long min, long target, long max) {
        return num(min) + ".." + num(target) + ".." + num(max);
    }

    private static String num(long v) {
        return v == Long.MAX_VALUE || v == LoadoutSpec.Pool.REMAINING
                ? "*"
                : v == LoadoutSpec.Pool.ROOM ? "room" : Long.toString(v);
    }

    private static List<Integer> ids(LoadoutSpec.Requirement r) {
        if (r instanceof LoadoutSpec.Item it) {
            return List.of(it.item().id());
        }
        if (r instanceof LoadoutSpec.Pool pool) {
            List<Integer> out = new ArrayList<>(pool.items().size());
            for (ItemRef m : pool.items()) {
                out.add(m.id());
            }
            return out;
        }
        return List.of();
    }

    private static String stock(AccountFacts account, List<Integer> ids) {
        long pack = 0;
        long bank = 0;
        for (int id : ids) {
            pack += account.inventory().count(NoteForm.BOTH, id);
            bank += account.bank().count(id);
        }
        return "pack " + pack + " + bank " + bank;
    }

    private static LoadoutSpec respec(LoadoutSpec loadout, List<LoadoutSpec.Requirement> bands, boolean anything) {
        return new LoadoutSpec(
                List.copyOf(bands), loadout.bankWhenFull(), anything, loadout.reserve(), loadout.banksUnnamed());
    }

    private static List<Route.Step> afterFirstStop(List<Route.Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Route.BankStop) {
                return steps.subList(i + 1, steps.size());
            }
        }
        return steps;
    }

    private static List<String> hopNames(List<Route.Step> steps) {
        List<String> names = new ArrayList<>();
        for (Route.Step s : steps) {
            if (s instanceof Route.Hop h && !names.contains(h.transport())) {
                names.add(h.transport());
            }
        }
        return names;
    }

    private static String hops(List<Route.Step> steps) {
        List<String> names = hopNames(steps);
        if (names.size() <= MAX_HOPS) {
            return names.toString();
        }
        int half = MAX_HOPS / 2;
        return "[" + String.join(", ", names.subList(0, half)) + ", …, "
                + String.join(", ", names.subList(names.size() - half, names.size())) + "]";
    }

    private static Map<Integer, Long> carried(AccountFacts account) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (InventorySlot s : account.inventory().slots()) {
            out.putIfAbsent(
                    s.item().id(),
                    account.inventory().count(NoteForm.BOTH, s.item().id()));
        }
        return out;
    }

    private static String name(ServerData env, int id) {
        return env.itemDefs().nameOf(id);
    }

    private static ItemRef refOf(int id) {
        return () -> id;
    }

    private static @Nullable Point representative(Dest goal) {
        if (goal instanceof Dest.AnyOf any) {
            return any.options().isEmpty() ? null : any.options().get(0).center();
        }
        return goal instanceof Area a ? a.center() : null;
    }
}
