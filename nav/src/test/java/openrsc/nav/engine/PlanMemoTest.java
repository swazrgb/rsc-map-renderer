package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.cache.CaffeineNavCaches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("one of nav's heaviest classes (0.3-0.5 GB live): alone, so its peak shares no heap")
class PlanMemoTest {

    @Test
    void aRepeatedPlanIsAHitAndEveryPlanMatchesAnEngineThatKeepsNothing() {
        ServerData env = RealDefs.serverData();
        Pathfinder cachedPf = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        Engine cached = (Engine) cachedPf.nav();
        Engine uncached = (Engine) RealDefs.pathfinder().nav();
        int[] gateItems = cachedPf.eligibilityItemIds();
        int[] gateQuests = cachedPf.eligibilityQuestIds();
        List<Area> sites = List.of(
                Point.of(122, 647), Point.of(310, 545), Point.of(220, 640), Point.of(651, 848), Point.of(120, 504));
        SplittableRandom rnd = new SplittableRandom(30);
        List<String> mismatches = new ArrayList<>();
        long hitsBefore = cached.planHits();
        int repeats = 0;
        for (int a = 0; a < 20; a++) {
            AccountFacts base = PrereqProgramTest.account(env, rnd, gateItems, gateQuests);
            Point from = Point.of(100 + rnd.nextInt(600), 450 + rnd.nextInt(400));
            Point site = (Point) sites.get(a % sites.size());
            // Carrying anything, the direct shape, or a coin band a bank stop settles.
            LoadoutSpec loadout = a % 2 == 0
                    ? LoadoutSpec.any()
                    : new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.COINS, 0, 10, 10, false)));

            PlanResult expected =
                    uncached.plan(new PlanRequest(base.at(from.x(), from.y()), from, site, loadout, NavPolicy.DEFAULT));
            PlanResult first =
                    cached.plan(new PlanRequest(base.at(from.x(), from.y()), from, site, loadout, NavPolicy.DEFAULT));
            PlanResult again = cached.plan(new PlanRequest(
                    base.at(from.x(), from.y()),
                    Point.of(from.x(), from.y()),
                    Point.of(site.x(), site.y()),
                    new LoadoutSpec(List.copyOf(loadout.requirements()), loadout.bankWhenFull(), loadout.anything()),
                    NavPolicy.DEFAULT));
            repeats++;
            if (!expected.equals(first) || !expected.equals(again)) {
                mismatches.add("account " + a + " " + from + " -> " + site + ": uncached " + expected + ", cached "
                        + first + ", again " + again);
            }
            assertSame(first, again, "account " + a + ": the walker's next tick is a hit");

            AccountFacts other =
                    PrereqProgramTest.account(env, rnd, gateItems, gateQuests).at(from.x(), from.y());
            PlanResult otherExpected = uncached.plan(new PlanRequest(other, from, site, loadout, NavPolicy.DEFAULT));
            PlanResult otherCached = cached.plan(new PlanRequest(other, from, site, loadout, NavPolicy.DEFAULT));
            if (!otherExpected.equals(otherCached)) {
                mismatches.add("other account " + a + ": uncached " + otherExpected + ", cached " + otherCached);
            }
            if (first instanceof PlanResult.Planned) {
                assertNotSame(first, otherCached, "account " + a + ": another account is not a hit");
            }
        }
        assertEquals(List.of(), mismatches);
        assertEquals(repeats, cached.planHits() - hitsBefore, "exactly the repeats are hits");
        assertEquals(0, uncached.planHits(), "an engine that keeps nothing keeps no plan");
    }
}
