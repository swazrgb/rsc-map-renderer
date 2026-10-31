package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.QuestId;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OneWayRegionEdgesTest {

    private static final NavPolicy POLICY = new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);

    private static ServerData env;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
    }

    @Test
    @DisplayName("the tutorial boatman rows a bot off the island to Lumbridge")
    void theBoatmanLeavesTutorialIsland() {
        Point boatRoom = Point.of(227, 754);
        Route route =
                planned(account(boatRoom, 1, new MutableQuests(), CacheValues.EMPTY), boatRoom, Point.of(120, 648));
        assertTrue(
                hops(route).stream().anyMatch(NpcRoutes.BOAT_TUTORIAL_ISLAND_TO_LUMBRIDGE.name()::equals),
                "the only way off the island is the boat: " + hops(route));
    }

    @Test
    @DisplayName("mid-quest, the east winch lowers a roped, permitted bot into the big cave")
    void theEastWinchReachesTheBigCave() {
        Point shaft = Point.of(25, 515);
        Route route = planned(digsiteAccount(shaft, true), shaft, Point.of(19, 3337));
        assertTrue(
                hops(route).stream()
                        .anyMatch(AllTransports.placementKey(
                                SceneryCrossings.DIG_SITE_WINCH_ROPE_CLIMB_DOWN_EAST_SHAFT_BEFORE_STAGE_6.name(),
                                Point.of(26, 515))::equals),
                "the rope down the east shaft is the way in: " + hops(route));
    }

    @Test
    @DisplayName("without the rope tied to the east bucket the big cave stays shut")
    void theBigCaveWantsTheRope() {
        Point shaft = Point.of(25, 515);
        PlanResult r = pf.nav()
                .plan(new PlanRequest(
                        digsiteAccount(shaft, false), shaft, Point.of(19, 3337), LoadoutSpec.any(), POLICY));
        assertInstanceOf(PlanResult.NoRoute.class, r, "the winch arm is gated on the rope, exactly as the server's is");
    }

    private static AccountFacts digsiteAccount(Point at, boolean ropeTied) {
        MutableQuests quests = new MutableQuests();
        quests.setStage(QuestId.DIGSITE.id(), 5);
        // The east winch wants the workman's permission, the rope tied to its bucket, and Agility 10:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/digsite/DigsiteWinch.java#L65
        CacheValues cache = new CacheValues(Map.of("digsite_winshaft", true, "winch_rope_2", ropeTied), Map.of());
        return account(at, 10, quests, cache);
    }

    private static AccountFacts account(Point at, int agility, MutableQuests quests, CacheValues cache) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        stats.setSkill(Skill.HITS.ordinal(), 10, 10, 0);
        stats.setSkill(Skill.AGILITY.ordinal(), agility, agility, 0);
        return new AccountFacts.Snapshot(
                env, new MutableInventory().snapshot(), Bank.empty(), stats.snapshot(), quests.snapshot(), at, cache);
    }

    private static Route planned(AccountFacts account, Point from, Point to) {
        PlanResult r = pf.nav().plan(new PlanRequest(account, from, to, LoadoutSpec.any(), POLICY));
        return assertInstanceOf(PlanResult.Planned.class, r, "no route " + from + " -> " + to)
                .route();
    }

    private static List<String> hops(Route r) {
        List<String> out = new ArrayList<>();
        for (Route.Step s : r.steps()) {
            if (s instanceof Route.Hop h) {
                out.add(h.transport());
            }
        }
        return out;
    }
}
