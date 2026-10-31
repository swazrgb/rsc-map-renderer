package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FareRepairTest {

    private static final Point LUMBRIDGE = Point.of(122, 647);
    private static final Point WEST_ARDOUGNE = Point.of(624, 576);
    private static final List<String> FENCED = List.of(
            "DRAGONSTONE_AMULET_EDGEVILLE",
            "YOUNG_TO_VILLAGE@160_453",
            "VILLAGE_TO_KHAZARD",
            "DRAGONSTONE_AMULET_DRAYNOR",
            "DRAGONSTONE_AMULET_AL_KHARID",
            "GLIDER_AL_KHARID_TO_GRAND_TREE",
            "STRONGHOLD_TO_KHAZARD",
            "ARDOUGNE_WALL_GATEWAY@622_588",
            "PLAGUE_CITY_DUG_UP_SOIL_447",
            "PLAGUE_CITY_SEWER_PIPE_TO_WEST_ARDOUGNE_FREE");

    private static ServerData env;
    private static Pathfinder pf;
    private static AccountFacts fresh;
    private static NavPolicy fenced;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        fresh = new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                Bank.empty(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                LUMBRIDGE);
        NavPolicy p = new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, true, Map.of(), false).withAllEligible();
        for (String name : FENCED) {
            p = p.prohibit(() -> name);
        }
        fenced = p;
    }

    @Test
    @DisplayName("a chain two fares deep is repaired to the lightest chain the pack can pay")
    void repairIsExactPastTwoUnpaidFares() {
        Route got = planned(fenced);

        // An empty pack pays for nothing, so the chains it can take are the fare-free ones: the lightest,
        // planned with every consuming transport prohibited, is the answer.
        NavPolicy fareFree = fenced;
        for (Transport t : pf.transports()) {
            if (!t.consumes(fresh).isEmpty()) {
                String name = t.name();
                fareFree = fareFree.prohibit(() -> name);
            }
        }
        Route want = planned(fareFree);

        for (Route.Step s : got.steps()) {
            if (s instanceof Route.Hop h) {
                assertTrue(h.fare().isEmpty(), "an empty pack pays no fare: " + h.transport());
            }
        }
        assertEquals(
                want.totalCostDeci(),
                got.totalCostDeci(),
                "the repair found a payable chain, but not the lightest one");
    }

    private static Route planned(NavPolicy policy) {
        PlanResult r = pf.nav().plan(new PlanRequest(fresh, LUMBRIDGE, WEST_ARDOUGNE, LoadoutSpec.any(), policy));
        return assertInstanceOf(PlanResult.Planned.class, r, "no route").route();
    }
}
