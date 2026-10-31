package openrsc.nav.planner;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.RouteContext;

/**
 * An account broken down into what transport gates read of it: each distinct fact ("agility at
 * least 32", "carrying a knife") and each distinct gate rule built from those facts, with whether it
 * holds and how many transports read it, plus the transports the account may use.
 */
public final class PathfinderAccount {

    private PathfinderAccount() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FactRow(String fact, boolean holds, int transports) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuleRow(String rule, boolean passes, int transports) {}

    /**
     * {@code facts} and {@code rules} come most-read first, {@code usable} by name, out of
     * {@code total} transports. {@code cacheAssumed} are the player-cache keys some gate reads that
     * the account holds no value for; a fact on such a key holds.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            List<FactRow> facts,
            List<RuleRow> rules,
            List<PathfinderInputs.Opening> usable,
            int total,
            List<String> cacheAssumed) {}

    /**
     * The breakdown for {@code req}'s account and policy, standing at {@code req.from()} when it is
     * set. A non-null {@code as} is used instead of {@code req.account()}.
     */
    public static Response of(Pathfinder pf, AccountFacts as, PathfinderPlans.Request req) {
        AccountFacts facts = as != null ? as : PathfinderPlans.probeFacts(pf.serverData(), req.account());
        NavPolicy policy = PathfinderPlans.policyOf(req.policy());
        AccountFacts here =
                req.from() == null ? facts : facts.at(req.from().x(), req.from().z());
        RouteContext ctx = new RouteContext(here, policy, pf.serverData());

        Map<Fact, Integer> factUses = new LinkedHashMap<>();
        IdentityHashMap<Prereq, Integer> ruleUses = new IdentityHashMap<>();
        Map<Fact, Boolean> truth = new HashMap<>();
        IdentityHashMap<Prereq, Boolean> passes = new IdentityHashMap<>();
        List<PathfinderInputs.Opening> usable = new ArrayList<>();
        // Gates share rules and rules share facts: each is evaluated once.
        for (Transport t : pf.transports()) {
            Prereq gate = t.prereq();
            if (gate != Prereq.ALWAYS) {
                ruleUses.merge(gate, 1, Integer::sum);
                for (Fact f : gate.facts()) {
                    factUses.merge(f, 1, Integer::sum);
                }
            }
            if (passes.computeIfAbsent(gate, p -> p.testFacts(f -> truth.computeIfAbsent(f, x -> x.test(ctx))))) {
                usable.add(PathfinderInputs.openingOf(t));
            }
        }

        List<FactRow> factRows = new ArrayList<>(factUses.size());
        TreeSet<String> cacheAssumed = new TreeSet<>();
        factUses.forEach((f, n) -> {
            factRows.add(new FactRow(f.toString(), truth.computeIfAbsent(f, x -> x.test(ctx)), n));
            boolean cache = f.kind() == Fact.Kind.CACHE_BOOL
                    || f.kind() == Fact.Kind.CACHE_INT_AT_LEAST
                    || f.kind() == Fact.Kind.CACHE_INT_EQUALS;
            if (cache && !here.cacheValues().tracks(f.cacheKey())) {
                cacheAssumed.add(f.cacheKey());
            }
        });
        factRows.sort(Comparator.comparingInt(FactRow::transports).reversed());

        List<RuleRow> ruleRows = new ArrayList<>(ruleUses.size());
        ruleUses.forEach((p, n) -> ruleRows.add(new RuleRow(p.toString(), passes.get(p), n)));
        ruleRows.sort(Comparator.comparingInt(RuleRow::transports).reversed());

        usable.sort(Comparator.comparing(PathfinderInputs.Opening::name));
        return new Response(factRows, ruleRows, usable, pf.transports().size(), List.copyOf(cacheAssumed));
    }
}
