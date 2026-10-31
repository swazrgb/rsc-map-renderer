package openrsc.nav.planner;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemRef;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.diagnose.Diagnosis;
import openrsc.nav.diagnose.NoRouteReport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.probe.NavProbe;
import openrsc.nav.probe.WalkSession;

/**
 * Plans one trip as a planning UI asks it: an account, a loadout to arrive holding, routing choices,
 * and two tiles. The answer is the walk simulated a tick at a time, the engine's route that walk was
 * chosen from, read-outs about the account and, when nothing routes, a {@link Diagnosis} of why.
 *
 * <p>A request is user input. Anything left out of it is a new account's: every skill at level 1,
 * hits at 10, no quests started, nothing carried or banked, a {@link LoadoutSpec#any()} loadout and
 * {@link NavPolicy#DEFAULT}.
 */
public final class PathfinderPlans {

    private static final int FRESH_LEVEL = 1;

    private static final int FRESH_HITS = 10;

    private PathfinderPlans() {}

    /** A tile; {@code z} is the map's y coordinate. */
    public record Tile(int x, int z) {}

    /**
     * The account to plan as; any field may be {@code null}. {@code skills} are levels by
     * {@link Skill} name in any case, clamped to 0..99, with unknown names dropped. {@code quests}
     * are stages by quest id ({@code -1} complete); {@code inventory} and {@code bank} amounts by item
     * id, none of it worn. {@code cache} holds the player-cache values the account knows: a gate fact
     * on any other key holds.
     */
    public record AccountInput(
            Map<String, Integer> skills,
            Map<Integer, Integer> quests,
            Map<Integer, Integer> inventory,
            Map<Integer, Integer> bank,
            CacheValues cache) {}

    /**
     * One thing to arrive holding: {@code item} for an exact id, else {@code anyOf} for whichever of
     * these, counted in pack slots and preferred in list order; a want with neither is ignored.
     * {@code min} defaults to 0, {@code target} to {@code min}, {@code max} to {@code target}, and a
     * {@code max} of {@code -1} has no ceiling. {@code worn} applies to an exact item only.
     */
    public record Want(Integer item, List<Integer> anyOf, Integer min, Integer target, Integer max, Boolean worn) {}

    /**
     * {@code keepEverythingElse} (default true) tolerates whatever the wants do not name; false banks
     * it. {@code bankWhenFull} (default false) makes a full pack a reason to stop at a bank.
     */
    public record LoadoutInput(Boolean keepEverythingElse, Boolean bankWhenFull, List<Want> wants) {}

    /**
     * {@code from} is required, and {@code to} for a plan. {@code as} names an account for the caller
     * to look up and pass in; nothing here reads it.
     */
    public record Request(
            Tile from, Tile to, String as, AccountInput account, LoadoutInput loadout, NavPolicy policy) {}

    /**
     * One tick of the simulated walk, the first at the start tile. {@code type} is {@code walk},
     * {@code hop} (at its landing tile) or {@code bank}. A hop carries {@code name}, {@code cost} on the scale where a walked tile is 10,
     * {@code req} and the item ids it {@code wears}. A bank stop carries {@code withdraw} and
     * {@code deposit} as the pack amount it leaves with, by item id, and {@code because}:
     * {@code "loadout"} and the names of later hops whose fare it draws.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            String type,
            int x,
            int z,
            String name,
            Integer cost,
            String req,
            Map<Integer, Long> withdraw,
            Map<Integer, Long> deposit,
            List<Integer> wears,
            List<String> because,
            List<PackItem> pack) {}

    /** One slot of the pack a step is walked with. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PackItem(int slot, int id, int amount, boolean equipped) {}

    /** Read-outs for the account at the start tile, where it may take {@code usableTransports}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Derived(
            int combatLevel, int questPoints, int wildernessLevel, int usableTransports, int totalTransports) {}

    /**
     * Where the answer's time went, in milliseconds. {@code estimateMs} and {@code botPlanMs} time the
     * trip's estimate and the first tick's plan before anything else has warmed the engine for them,
     * and {@code botWeightSetsBuilt} and {@code botLegPlanesBuilt} count the routing weight sets and
     * leg distance planes the engine had to build for those two. The {@code simulated} counts and
     * times cover every tick of the walk; {@code diagnosisMs} is 0 when it routes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Timings(
            double totalMs,
            double estimateMs,
            double botPlanMs,
            long botWeightSetsBuilt,
            long botLegPlanesBuilt,
            int simulatedTicks,
            int simulatedPlans,
            int simulatedWalks,
            double simulationPlanMs,
            double simulationWalkMs,
            double derivedMs,
            double diagnosisMs) {}

    /**
     * One step of the engine's route. A {@code cross} spans {@code (x, z)} to {@code (x2, z2)}; a
     * {@code hop} goes from its entry tile to its landing at {@code (x2, z2)}, with its {@code name};
     * a {@code bank} is the booth; {@code arrive} spans the destination's bounds.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanStep(String type, int x, int z, Integer x2, Integer z2, String name) {}

    /** A {@link Diagnosis} as the wire carries it, its kinds by enum name. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Why(String kind, String summary, List<Fix> fixes, boolean complete) {}

    /** A {@link Diagnosis.Fix} as the wire carries it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fix(String kind, String summary, List<Blocker> blockers, List<String> hops) {}

    /** A {@link Diagnosis.Blocker} as the wire carries it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Blocker(
            String what,
            String why,
            String fix,
            List<Integer> items,
            List<Integer> quests,
            List<String> skills,
            List<String> cache,
            String transport) {}

    /**
     * {@code ticks} is the walk's length: a tick per tile moved, plus each hop's cost at 10 to a tick.
     * When nothing routes, {@code diagnosis} is the reason as one line and {@code why} the same
     * verdict structured; a preview has neither, and an account the request cannot describe has only
     * the line. {@code account} is {@code null} for both.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            boolean found,
            int ticks,
            List<Step> steps,
            List<PlanStep> plan,
            Derived account,
            String diagnosis,
            Why why,
            Timings timings) {}

    /**
     * {@code req}'s walk as a {@link WalkSession}, to be stepped a tick at a time. A non-null
     * {@code as} is planned as instead of {@code req.account()}.
     *
     * @throws IllegalArgumentException when the request's pack needs more slots than a pack has
     */
    public static WalkSession session(Pathfinder pf, AccountFacts as, Request req) {
        AccountFacts facts = as != null ? as : probeFacts(pf.serverData(), req.account());
        return new WalkSession(
                pf,
                facts,
                policyOf(req.policy()),
                loadoutOf(req.loadout()),
                Point.of(req.from().x(), req.from().z()),
                Point.of(req.to().x(), req.to().z()),
                new NavProbe.SimulationStats());
    }

    /**
     * Plans {@code req}, diagnosing a failure. A non-null {@code as} is planned as instead of
     * {@code req.account()}; a pack the request overfills is answered as not found, the reason as
     * its {@code diagnosis}.
     */
    public static Response plan(Pathfinder pf, AccountFacts as, Request req) {
        return answer(pf, as, req, false);
    }

    /** {@link #plan} reduced to the walk and the engine's route: no estimate, read-outs or diagnosis. */
    public static Response preview(Pathfinder pf, AccountFacts as, Request req) {
        return answer(pf, as, req, true);
    }

    private static Response answer(Pathfinder pf, AccountFacts as, Request req, boolean preview) {
        long t0 = System.nanoTime();
        ServerData env = pf.serverData();
        AccountFacts facts;
        try {
            facts = as != null ? as : probeFacts(env, req.account());
        } catch (IllegalArgumentException e) {
            return new Response(
                    false,
                    0,
                    List.of(),
                    List.of(),
                    null,
                    e.getMessage(),
                    null,
                    new Timings(ms(System.nanoTime() - t0), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        NavPolicy policy = policyOf(req.policy());
        LoadoutSpec loadout = loadoutOf(req.loadout());

        Point from = Point.of(req.from().x(), req.from().z());
        Point to = Point.of(req.to().x(), req.to().z());
        AccountFacts here = NavProbe.factsFor(facts, pf, from.x(), from.y());
        NavPolicy botPolicy = NavProbe.policyFor(facts, policy);
        // The estimate runs first so that nothing has warmed the engine for it. What it builds is counted
        // with the first plan's builds: it may have built what that plan needs.
        long[] workBefore = pf.nav().workCounters();
        long mark = System.nanoTime();
        if (!preview) {
            pf.nav().estimate(here, from, to, botPolicy);
        }
        double estimateMs = ms(System.nanoTime() - mark);
        long[] workAfter = pf.nav().workCounters();

        NavProbe.SimulationStats sim = new NavProbe.SimulationStats();
        List<NavProbe.ProbeStep> walk =
                NavProbe.planBetween(pf, from.x(), from.y(), to.x(), to.y(), facts, policy, loadout, sim);
        double botPlanMs = ms(sim.firstPlanNanos);
        mark = System.nanoTime();
        Derived derived = preview ? null : derived(pf, facts, policy, req.from());
        double derivedMs = ms(System.nanoTime() - mark);
        double diagnosisMs = 0;
        String diagnosis = null;
        Why why = null;
        if (walk == null && !preview) {
            mark = System.nanoTime();
            Diagnosis d = NoRouteReport.explain(
                    pf.nav(),
                    facts.at(from.x(), from.y()),
                    pf.serverData().collisionMap(),
                    from,
                    to,
                    loadout,
                    policy,
                    true);
            diagnosis = d.summary();
            why = whyOf(d);
            diagnosisMs = ms(System.nanoTime() - mark);
        }
        Timings timings = new Timings(
                ms(System.nanoTime() - t0),
                estimateMs,
                botPlanMs,
                workAfter[0] - workBefore[0] + sim.firstPlanBuilt[0],
                workAfter[1] - workBefore[1] + sim.firstPlanBuilt[1],
                sim.ticks,
                sim.plans,
                sim.walks,
                ms(sim.planNanos),
                ms(sim.walkNanos),
                derivedMs,
                diagnosisMs);

        if (walk == null) {
            return new Response(false, 0, List.of(), List.of(), derived, diagnosis, why, timings);
        }
        List<Step> steps = new ArrayList<>(walk.size());
        int cost = 0;
        NavProbe.ProbeStep prev = null;
        for (NavProbe.ProbeStep s : walk) {
            if (s.hopName() != null) {
                int hopCost = s.hopCost() == null ? 0 : s.hopCost();
                cost += hopCost;
                steps.add(new Step(
                        "hop",
                        s.x(),
                        s.y(),
                        s.hopName(),
                        hopCost,
                        s.requirements(),
                        null,
                        null,
                        List.copyOf(s.wears()),
                        s.because(),
                        packOf(s.pack())));
            } else if (s.bankVisit()) {
                steps.add(new Step(
                        "bank",
                        s.x(),
                        s.y(),
                        null,
                        null,
                        null,
                        s.withdraw(),
                        s.deposit(),
                        List.of(),
                        s.because(),
                        packOf(s.pack())));
            } else {
                // A player moves one tile a tick.
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/WalkingQueue.java#L79
                if (prev != null && (prev.x() != s.x() || prev.y() != s.y())) {
                    cost += 10;
                }
                steps.add(new Step(
                        "walk", s.x(), s.y(), null, null, null, null, null, List.of(), List.of(), packOf(s.pack())));
            }
            prev = s;
        }
        return new Response(true, Math.round(cost / 10f), steps, planOf(sim.enginePlan), derived, null, null, timings);
    }

    private static Why whyOf(Diagnosis d) {
        List<Fix> fixes = new ArrayList<>(d.fixes().size());
        for (Diagnosis.Fix f : d.fixes()) {
            List<Blocker> blockers = new ArrayList<>(f.blockers().size());
            for (Diagnosis.Blocker b : f.blockers()) {
                blockers.add(new Blocker(
                        b.what(), b.why(), b.fix(), b.items(), b.quests(), b.skills(), b.cache(), b.transport()));
            }
            fixes.add(new Fix(f.kind().name(), f.summary(), blockers, f.hops()));
        }
        return new Why(d.kind().name(), d.summary(), fixes, d.complete());
    }

    /** {@code route}'s steps as {@link PlanStep}s; empty for {@code null}. */
    public static List<PlanStep> planOf(Route route) {
        if (route == null) {
            return List.of();
        }
        List<PlanStep> out = new ArrayList<>(route.steps().size());
        for (Route.Step s : route.steps()) {
            switch (s) {
                case Route.Cross c ->
                    out.add(new PlanStep(
                            "cross",
                            c.span().minX(),
                            c.span().minY(),
                            c.span().maxX(),
                            c.span().maxY(),
                            null));
                case Route.Hop h ->
                    out.add(new PlanStep(
                            "hop",
                            h.entry().x(),
                            h.entry().y(),
                            h.landing().x(),
                            h.landing().y(),
                            h.transport()));
                case Route.BankStop b ->
                    out.add(new PlanStep("bank", b.bank().x(), b.bank().y(), null, null, null));
                case Route.Arrive a ->
                    out.add(new PlanStep(
                            "arrive",
                            a.target().minX(),
                            a.target().minY(),
                            a.target().maxX(),
                            a.target().maxY(),
                            null));
            }
        }
        return out;
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    static AccountFacts probeFacts(ServerData env, AccountInput in) {
        return probeFacts(env, in, in == null || in.inventory() == null ? Map.of() : in.inventory(), Set.of());
    }

    /** {@code in}'s account, carrying {@code carried} by item id, wearing {@code worn}, instead of its own pack. */
    static AccountFacts probeFacts(ServerData env, AccountInput in, Map<Integer, Integer> carried, Set<Integer> worn) {
        Map<Skill, Integer> levels = new LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            levels.put(s, s == Skill.HITS ? FRESH_HITS : FRESH_LEVEL);
        }
        if (in != null && in.skills() != null) {
            in.skills().forEach((name, level) -> {
                try {
                    levels.put(
                            Skill.valueOf(name.toUpperCase(java.util.Locale.ROOT)), Math.max(0, Math.min(99, level)));
                } catch (IllegalArgumentException ignored) {
                    // A request is user input: an unknown skill name is dropped rather than failing the plan.
                }
            });
        }
        return new InputsProbeBot(
                env,
                levels,
                carried,
                worn,
                in == null || in.bank() == null ? Map.of() : in.bank(),
                in == null || in.quests() == null ? Map.of() : in.quests(),
                in == null || in.cache() == null ? CacheValues.EMPTY : in.cache());
    }

    static NavPolicy policyOf(NavPolicy in) {
        return in == null ? NavPolicy.DEFAULT : in;
    }

    private static List<PackItem> packOf(Inventory pack) {
        if (pack == null) {
            return List.of();
        }
        List<PackItem> out = new ArrayList<>(pack.slots().size());
        for (InventorySlot slot : pack.slots()) {
            out.add(new PackItem(slot.slot(), slot.item().id(), slot.amount(), slot.equipped()));
        }
        return out;
    }

    private static final int UNBOUNDED = -1;

    // A pool's max is capped at a full pack.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L30
    private static final int PACK_SLOTS = 30;

    private static LoadoutSpec loadoutOf(LoadoutInput in) {
        boolean keepRest = in == null || in.keepEverythingElse() == null || in.keepEverythingElse();
        boolean bankWhenFull = in != null && Boolean.TRUE.equals(in.bankWhenFull());
        List<Want> wants = in == null || in.wants() == null ? List.of() : in.wants();
        List<LoadoutSpec.Requirement> reqs = new ArrayList<>(wants.size());
        for (Want w : wants) {
            long min = w.min() == null ? 0 : Math.max(0, w.min());
            long target = w.target() == null ? min : Math.max(min, w.target());
            long max = w.max() == null ? target : w.max() == UNBOUNDED ? Long.MAX_VALUE : Math.max(target, w.max());
            if (w.item() != null) {
                reqs.add(new LoadoutSpec.Item(ref(w.item()), min, target, max, Boolean.TRUE.equals(w.worn())));
            } else if (w.anyOf() != null && !w.anyOf().isEmpty()) {
                List<ItemRef> members = new ArrayList<>(w.anyOf().size());
                w.anyOf().forEach(id -> members.add(ref(id)));
                reqs.add(new LoadoutSpec.Pool(members, (int) min, (int) target, (int) Math.min(max, PACK_SLOTS)));
            }
        }
        if (reqs.isEmpty()) {
            return keepRest ? LoadoutSpec.any() : LoadoutSpec.empty();
        }
        return new LoadoutSpec(reqs, bankWhenFull, keepRest);
    }

    private static ItemRef ref(int id) {
        return () -> id;
    }

    private static Derived derived(Pathfinder pf, AccountFacts facts, NavPolicy policy, Tile from) {
        RouteContext ctx = new RouteContext(facts.at(from.x(), from.z()), policy, pf.serverData());
        Map<Fact, Boolean> truth = new HashMap<>();
        IdentityHashMap<Prereq, Boolean> passes = new IdentityHashMap<>();
        int usable = 0;
        for (Transport t : pf.transports()) {
            if (passes.computeIfAbsent(t.prereq(), p -> p.testFacts(f -> truth.computeIfAbsent(f, x -> x.test(ctx))))) {
                usable++;
            }
        }
        return new Derived(
                AccountFacts.combatLevel(facts.stats()),
                facts.quests().questPoints(),
                AccountFacts.wildernessLevel(from.x(), from.z()),
                usable,
                pf.transports().size());
    }
}
