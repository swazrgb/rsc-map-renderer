package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Map;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.cache.NavCaches;
import org.junit.jupiter.api.Test;

class CarolPartyStairsTest {
    @Test
    void observed_closure_blocks_the_party_room_and_invitation_reopens_it() {
        var env = RealDefs.serverData(RealDefs.COLESLAW);
        var pathfinder = Pathfinder.load(env, NavCaches.none());
        Point from = Point.of(318, 546), to = Point.of(320, 1490);
        assertFalse(env.collisionMap().fullyBlocked(from.x(), from.y()), "stairs entry must be walkable");
        assertFalse(env.collisionMap().fullyBlocked(to.x(), to.y()), "party destination must be walkable");
        MutableStats stats = new MutableStats();
        for (Skill skill : Skill.values()) {
            stats.setSkill(skill.ordinal(), 70, 70, 0);
        }
        // An unobserved flag counts as open: only a closure the bot has seen blocks the stairs.
        for (Map<String, Boolean> flags : java.util.List.of(
                Map.of("carol.partyAccess", false), Map.of("carol.partyAccess", true), Map.<String, Boolean>of())) {
            var account = new AccountFacts.Snapshot(
                    env,
                    new MutableInventory().snapshot(),
                    Bank.empty(),
                    stats.snapshot(),
                    new MutableQuests().snapshot(),
                    from,
                    new CacheValues(flags, Map.of()));
            var result =
                    pathfinder.nav().plan(new PlanRequest(account, from, to, LoadoutSpec.any(), NavPolicy.DEFAULT));
            if (flags.getOrDefault("carol.partyAccess", true)) {
                assertInstanceOf(PlanResult.Planned.class, result, () -> "flags=" + flags + ", result=" + result);
            } else {
                assertInstanceOf(PlanResult.NoRoute.class, result, () -> "flags=" + flags + ", result=" + result);
            }
        }
    }
}
