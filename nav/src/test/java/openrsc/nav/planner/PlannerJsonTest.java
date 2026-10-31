package openrsc.nav.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.cache.CacheValues;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.nav.BerthPolicy;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PlannerJsonTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();
    private static Pathfinder pathfinder;

    @BeforeAll
    static void build() {
        pathfinder = RealDefs.pathfinder();
    }

    private static PathfinderPlans.Request gundai() {
        int shark = ItemId.SHARK.id();
        // Exercises every reader: a pool want, both kinds of cache value, a berth policy with exceptions.
        return new PathfinderPlans.Request(
                new PathfinderPlans.Tile(223, 105),
                new PathfinderPlans.Tile(223, 108),
                null,
                new PathfinderPlans.AccountInput(
                        Map.of("AGILITY", 40),
                        Map.of(12, -1),
                        Map.of(shark, 30),
                        Map.of(ItemId.COINS.id(), 100, 12, 1, 13, 1),
                        new CacheValues(Map.of("barcrawl_completed", false), Map.of("tutorial", 3))),
                new PathfinderPlans.LoadoutInput(
                        false,
                        false,
                        List.of(
                                new PathfinderPlans.Want(shark, null, 30, 30, 30, false),
                                new PathfinderPlans.Want(null, List.of(12, 13), 1, null, null, true))),
                NavPolicy.DEFAULT
                        .withBerth(BerthPolicy.all().exceptId(47).exceptIdx(9))
                        .withAvoidSkulled(false));
    }

    @Test
    void aRequestReadsAsItWasWritten() {
        PathfinderPlans.Request want = gundai();
        assertEquals(want, PlannerJson.request(JACKSON.writeValueAsString(want)));
    }

    @Test
    void aPlanWritesAsJacksonWritesIt() {
        int shark = ItemId.SHARK.id();
        PathfinderPlans.Request ask = gundai();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                new PathfinderPlans.Request(
                        ask.from(),
                        ask.to(),
                        null,
                        new PathfinderPlans.AccountInput(
                                Map.of(),
                                Map.of(),
                                Map.of(shark, 30),
                                ask.account().bank(),
                                null),
                        new PathfinderPlans.LoadoutInput(
                                false, false, List.of(new PathfinderPlans.Want(shark, null, 30, 30, 30, false))),
                        ask.policy()));
        assertTrue(r.found(), r.diagnosis());
        assertTrue(r.steps().stream().anyMatch(s -> s.type().equals("hop")), "a hop is pinned");
        assertTrue(r.steps().stream().anyMatch(s -> s.type().equals("bank")), "a bank stop is pinned");
        assertSameJson(JACKSON.writeValueAsString(r), PlannerJson.write(r));
    }

    @Test
    void aFailedPlanWritesAsJacksonWritesIt() {
        PathfinderPlans.Response r = PathfinderPlans.plan(pathfinder, null, gundai());
        assertFalse(r.found());
        assertSameJson(JACKSON.writeValueAsString(r), PlannerJson.write(r));
    }

    @Test
    void anImpossibleAccountWritesAsJacksonWritesIt() {
        PathfinderPlans.Request ask = gundai();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                new PathfinderPlans.Request(
                        ask.from(),
                        ask.to(),
                        null,
                        new PathfinderPlans.AccountInput(null, null, Map.of(ItemId.SHARK.id(), 31), null, null),
                        ask.loadout(),
                        ask.policy()));
        assertNull(r.account(), "the account is the null this pins: " + r.diagnosis());
        assertSameJson(JACKSON.writeValueAsString(r), PlannerJson.write(r));
    }

    @Test
    void theCatalogueWritesAsJacksonWritesIt() {
        PathfinderInputs.Response r = PathfinderInputs.of(pathfinder);
        assertSameJson(JACKSON.writeValueAsString(r), PlannerJson.write(r));
    }

    @Test
    void theAccountWritesAsJacksonWritesIt() {
        PathfinderAccount.Response r = PathfinderAccount.of(pathfinder, null, gundai());
        assertSameJson(JACKSON.writeValueAsString(r), PlannerJson.write(r));
    }

    @Test
    void itemNamesWriteAsTheRunnerWritesThem() {
        ItemDefs defs = pathfinder.serverData().itemDefs();
        int max = pathfinder.serverData().world().rules().maxItemId();
        Map<Integer, String> names = new LinkedHashMap<>();
        for (int id = 0; id < defs.size() && (max == ServerRules.UNRESTRICTED || id <= max); id++) {
            if (defs.get(id) != null) {
                names.put(id, defs.nameOf(id));
            }
        }
        String ours = PlannerJson.itemNames(defs, max);
        assertEquals(JACKSON.readTree(JACKSON.writeValueAsString(names)), JACKSON.readTree(ours));
        assertEquals(
                "Coins",
                JACKSON.readTree(ours).get(String.valueOf(ItemId.COINS.id())).asString());
    }

    private static void assertSameJson(String jackson, String ours) {
        JsonNode a = JACKSON.readTree(jackson);
        JsonNode b = JACKSON.readTree(ours);
        assertEquals(a, b);
        assertNoNullField(a, "$");
    }

    private static void assertNoNullField(JsonNode n, String path) {
        if (n.isObject()) {
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                assertFalse(e.getValue().isNull(), path + "." + e.getKey() + " is written as null");
                assertNoNullField(e.getValue(), path + "." + e.getKey());
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                assertNoNullField(n.get(i), path + "[" + i + "]");
            }
        }
    }
}
