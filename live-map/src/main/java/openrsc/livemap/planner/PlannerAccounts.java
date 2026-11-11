package openrsc.livemap.planner;

import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import org.jspecify.annotations.Nullable;

/** The accounts the route planner can plan as, by name; without any, it plans only accounts described by hand. */
@FunctionalInterface
public interface PlannerAccounts {

    /** The named account as it stands; null when there is no account of that name. */
    @Nullable
    AccountFacts account(String name);

    /**
     * The routing a plan as {@code name} takes when {@code asked} for: what the application's walk of that account
     * can take. {@code asked} itself, by default.
     */
    default NavPolicy routing(Pathfinder pf, String name, NavPolicy asked) {
        return asked;
    }
}
