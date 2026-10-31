package openrsc.nav.eligibility;

import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.nav.NavPolicy;

/**
 * What a {@link Prereq} is evaluated against: the account's state, the caller's routing policy, and
 * the world whose item definitions classify what the account carries. All three are required; the
 * constructor throws {@link IllegalArgumentException} on a {@code null}.
 */
public record RouteContext(AccountFacts account, NavPolicy policy, ServerData serverData) {

    public RouteContext {
        if (account == null || policy == null || serverData == null) {
            throw new IllegalArgumentException("RouteContext components are mandatory");
        }
    }

    /** The same policy and world over another view of the account, such as the pack after a bank stop. */
    public RouteContext withAccount(AccountFacts projected) {
        return new RouteContext(projected, policy, serverData);
    }
}
