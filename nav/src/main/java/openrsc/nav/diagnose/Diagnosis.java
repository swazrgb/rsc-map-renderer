package openrsc.nav.diagnose;

import java.util.List;
import java.util.StringJoiner;
import org.jetbrains.annotations.Nullable;

/**
 * Why a plan came back {@link openrsc.nav.PlanResult.NoRoute}, as {@link NoRouteReport#explain}
 * found it: the rung that caught the failure and the fixes that would open the trip, each naming
 * what to change.
 *
 * <p>Fixes are found by re-planning with things changed: a fix's changes together make the trip
 * plan, and dropping any one of them does not. A verdict that could not show this says so in its
 * summary, such as closed hops that, all lifted, still do not route.
 *
 * @param kind the rung that caught the failure; with several fixes, the first fix's
 * @param summary the verdict as a sentence for a person
 * @param fixes any one of them opens the trip; fewest changes first, then the cheapest route.
 *     Empty when the verdict names nothing to change
 * @param complete false when the search for fixes stopped at its budget, so more may exist
 */
public record Diagnosis(Kind kind, String summary, List<Fix> fixes, boolean complete) {

    public Diagnosis {
        fixes = List.copyOf(fixes);
    }

    /**
     * One way to make the trip plan: every blocker in it must go, and no smaller set of them does.
     *
     * @param kind this fix's own rung, which may differ from the verdict's: a policy knob in one fix,
     *     an account gate in the next
     * @param hops the distinct transports of the route this fix opens, in order
     */
    public record Fix(Kind kind, String summary, List<Blocker> blockers, List<String> hops) {

        public Fix {
            blockers = List.copyOf(blockers);
            hops = List.copyOf(hops);
        }
    }

    /**
     * The rung that caught a failure. Rungs are asked in declaration order, so a later kind means
     * every earlier one was tried and did not route.
     */
    public enum Kind {
        /** Nothing can stand on the destination's tile. */
        DESTINATION,
        /** The quest stages were not known, and the trip routes once every quest is done. */
        QUESTS_UNKNOWN,
        /** An arrival band the trip cannot satisfy: dropping or lowering it routes. */
        LOADOUT,
        /** The bands route, but an item the loadout does not name cannot be banked on the way. */
        SHED,
        /** The trip routes only arriving empty: something carried is refused on the only way in. */
        PACK,
        /** Hops the trip needs are closed to this account. */
        ELIGIBILITY,
        /** The policy shuts every way there, possibly beside a gate the account also fails. */
        POLICY,
        /** Nothing the report can lift routes it: the map and the transport graph have no way there. */
        GEOGRAPHY,
        /** The ask plans as it stands, so the failure was not the route; or the investigation threw. */
        UNDIAGNOSED
    }

    /**
     * One thing standing in the way, and the change that removes it. The id lists let a UI find the
     * row of each thing named.
     *
     * @param what the culprit as the request states it, such as {@code "Rope ×1 in the pack"}
     * @param why what about it blocks, as a sentence; may be empty
     * @param fix the change to make, as an instruction
     * @param items item ids of the band or carried item, or that a failed gate wants carried or worn
     * @param quests quest ids a failed gate waits on
     * @param skills {@code Skill} names a failed gate waits on
     * @param cache cache keys a failed gate waits on
     * @param transport the hop to blame, or {@code null} when no single hop is
     */
    public record Blocker(
            String what,
            String why,
            String fix,
            List<Integer> items,
            List<Integer> quests,
            List<String> skills,
            List<String> cache,
            @Nullable String transport) {

        public Blocker {
            items = List.copyOf(items);
            quests = List.copyOf(quests);
            skills = List.copyOf(skills);
            cache = List.copyOf(cache);
        }

        public static Blocker ofItems(
                String what, String why, String fix, List<Integer> items, @Nullable String transport) {
            return new Blocker(what, why, fix, items, List.of(), List.of(), List.of(), transport);
        }
    }

    /**
     * The verdict on one line, for a log or an exception message: the kind, then each fix's blockers
     * joined by {@code " OR "}, or the summary when no fix names one.
     */
    public String line() {
        StringJoiner fixes = new StringJoiner(" OR ");
        for (Fix f : this.fixes) {
            StringJoiner out = new StringJoiner("; ");
            for (Blocker b : f.blockers()) {
                out.add(b.what() + (b.why().isEmpty() ? "" : " — " + b.why()));
            }
            if (out.length() > 0) {
                fixes.add(out.toString());
            }
        }
        return kind + ": " + (fixes.length() == 0 ? summary : fixes.toString())
                + (complete ? "" : " OR … (more not listed)");
    }
}
