package openrsc.nav.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.defs.NpcDef;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BobMapCampGeneratorTest {

    private static Pathfinder pathfinder;
    private static ServerData env;

    @BeforeAll
    static void build() {
        env = RealDefs.serverData();
        pathfinder = RealDefs.cachedPathfinder();
    }

    @Test
    void cowsEastOfLumbridgeOpenTheFieldGateOnBothRoutes() {
        BobMapCampGenerator.Response r = compile(nearest("Cow", 100, 610, 3));
        assertNull(r.error(), r.error());
        List<String> lines = r.recipe().lines().map(String::trim).toList();
        assertEquals(3, lines.stream().filter(l -> l.startsWith("target ")).count());
        assertTrue(lines.contains("route bank") && lines.contains("route camp"), r.recipe());
        assertEquals(2, lines.stream().filter(l -> l.equals("end")).count());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("gate ") || l.startsWith("door ")), r.recipe());
    }

    @Test
    void aCampUnderGroundClimbsWithOneClick() {
        BobMapCampGenerator.Response r = compile(nearest("Giant", 208, 3320, 2));
        assertNull(r.error(), r.error());
        assertTrue(
                r.recipe().lines().map(String::trim).anyMatch(l -> l.startsWith("obj ") || l.startsWith("wall ")),
                r.recipe());
    }

    @Test
    void walkStepsAreCloseEnoughForAClientsOwnWalk() {
        BobMapCampGenerator.Response r = compile(nearest("Goblin", 130, 650, 2));
        assertNull(r.error(), r.error());
        int[] last = null;
        for (String line : r.recipe().lines().map(String::trim).toList()) {
            String[] w = line.split("\\s+");
            if (w[0].equals("route")) {
                last = null;
            } else if (w[0].equals("walk")) {
                int[] at = {Integer.parseInt(w[1]), Integer.parseInt(w[2])};
                if (last != null) {
                    int d = Math.max(Math.abs(at[0] - last[0]), Math.abs(at[1] - last[1]));
                    assertTrue(d <= BobMapCampGenerator.WAYPOINT_TILES, "a walk of " + d + " tiles: " + line);
                }
                last = at;
            } else if (w[0].equals("obj") || w[0].equals("wall")) {
                last = new int[] {Integer.parseInt(w[w.length - 4]), Integer.parseInt(w[w.length - 3])};
            }
        }
    }

    @Test
    void onlySimpleClicksAreTaken() {
        PathfinderPlans.AccountInput account = null;
        var facts = PathfinderPlans.probeFacts(env, account);
        int simple = 0;
        List<String> sideless = new ArrayList<>();
        for (Transport t : pathfinder.transports()) {
            if (BobMapCampGenerator.simple(t, facts)) {
                simple++;
                assertTrue(t.consumes(facts).isEmpty(), t.name());
                // A step names its wall by side: a wall matched on any side would be clicked on the wrong one.
                if (((DeclarativeTransport) t).engageSpec().target() instanceof EngageSpec.Target.Boundary w
                        && w.dir() < 0) {
                    sideless.add(t.name());
                }
            }
        }
        assertTrue(simple > 100, "simple transports: " + simple);
        assertEquals(List.of(), sideless);
        assertTrue(
                BobMapCampGenerator.clickOnly(pathfinder, facts).costOverrides().size() > 0);
        assertFalse(BobMapCampGenerator.clickOnly(pathfinder, facts).teleportSpells());
    }

    @Test
    void aClickThatWantsAnItemCarriedIsTakenWhenTheLoadoutCarriesIt() {
        var facts = PathfinderPlans.probeFacts(env, null);
        Transport gated = null;
        for (Transport t : pathfinder.transports()) {
            boolean wantsItem = false;
            for (var f : t.prereq().facts()) {
                wantsItem |= f.kind() == openrsc.nav.eligibility.EligibilityInputs.Fact.Kind.CARRIED_AT_LEAST;
            }
            if (wantsItem && BobMapCampGenerator.simple(t, facts)) {
                gated = t;
                break;
            }
        }
        assertNotNull(gated, "a one-click transport gated on carrying an item");
        int item = -1;
        for (var f : gated.prereq().facts()) {
            if (f.kind() == openrsc.nav.eligibility.EligibilityInputs.Fact.Kind.CARRIED_AT_LEAST) {
                item = f.itemId();
            }
        }
        var carrying = PathfinderPlans.probeFacts(env, null, Map.of(item, 1), java.util.Set.of());
        assertEquals(1, carrying.inventory().count(item), gated.name());
        assertEquals(0, facts.inventory().count(item), gated.name());
    }

    @Test
    void aCarriedKnifeCutsTheWebsToANearerBank() {
        List<Integer> targets = List.of(51, 47, 48, 46, 45, 49);
        BobMapCampGenerator.Response bare = BobMapCampGenerator.compile(pathfinder, request(targets, List.of()));
        BobMapCampGenerator.Response knife = BobMapCampGenerator.compile(
                pathfinder, request(targets, List.of(new BobMapCampGenerator.Item(13, 1, 1, false))));
        assertNull(knife.error(), knife.error());
        assertTrue(knife.recipe().lines().map(String::trim).anyMatch(l -> l.startsWith("cut ")), knife.recipe());
        assertFalse(bare.recipe().lines().map(String::trim).anyMatch(l -> l.startsWith("cut ")), bare.recipe());
    }

    @Test
    void routesTakeEverySkillAt99SoTheLockpickPicksThePirateHideoutDoor() {
        List<BobMapCampGenerator.Item> kit =
                List.of(new BobMapCampGenerator.Item(13, 1, 1, false), new BobMapCampGenerator.Item(714, 1, 1, false));
        BobMapCampGenerator.Response r = BobMapCampGenerator.compile(pathfinder, request(List.of(61, 64, 60), kit));
        assertNull(r.error(), r.error());
        assertTrue(
                r.recipe()
                        .lines()
                        .map(String::trim)
                        .anyMatch(l -> l.startsWith("wall ") && l.contains("DOOR_PIRATE_HIDEOUT")),
                r.recipe());
    }

    @Test
    void anIslandOnlyAShipReachesHasNoWalkToABank() {
        ServerData.NpcSpawn priest = env.npcSpawnByServerIndex(895);
        assertEquals("high priest of entrana", env.npc(priest.id()).name().toLowerCase(java.util.Locale.ROOT));
        BobMapCampGenerator.Response r = BobMapCampGenerator.compile(pathfinder, request(List.of(895), List.of()));
        assertNotNull(r.error(), r.recipe());
    }

    private static BobMapCampGenerator.Request request(List<Integer> targets, List<BobMapCampGenerator.Item> items) {
        return new BobMapCampGenerator.Request(targets, items, List.of(), null, null, 1, List.of(), List.of(), null);
    }

    @Test
    void respawnTicksRoundUp() {
        NpcDef cow = env.npc(spawnId("Cow"));
        int ticks = BobMapCampGenerator.respawnTicks(env, cow);
        long ms = (long) (cow.respawnTime() * env.world().rules().npcRespawnMultiplier() * 1000);
        int tick = env.world().rules().gameTickMs();
        assertTrue(ticks * (long) tick >= ms && (ticks - 1) * (long) tick < ms, ticks + " ticks for " + ms + " ms");
    }

    @Test
    void anUnknownSpawnIsRefused() {
        BobMapCampGenerator.Response r = compile(List.of(Integer.MAX_VALUE));
        assertNotNull(r.error());
    }

    @Test
    void onlyFoodIsEaten() {
        List<Integer> cows = nearest("Cow", 100, 610, 1);
        BobMapCampGenerator.Response r = BobMapCampGenerator.compile(
                pathfinder,
                new BobMapCampGenerator.Request(
                        cows,
                        List.of(new BobMapCampGenerator.Item(13, 1, 1, false)),
                        List.of(13),
                        7,
                        null,
                        1,
                        List.of(),
                        List.of(),
                        null));
        assertEquals("Knife is not food", r.error());
    }

    @Test
    void onlyBonesAreBuried() {
        BobMapCampGenerator.Request req = new BobMapCampGenerator.Request(
                nearest("Cow", 100, 610, 1), List.of(), List.of(), null, null, 1, List.of(), List.of(373), null);
        assertEquals(
                "Lobster cannot be buried",
                BobMapCampGenerator.compile(pathfinder, req).error());
    }

    @Test
    void sleepingCarriesASleepingBag() {
        BobMapCampGenerator.Response r = BobMapCampGenerator.compile(
                pathfinder,
                new BobMapCampGenerator.Request(
                        nearest("Cow", 100, 610, 1), List.of(), List.of(), null, 50, 1, List.of(), List.of(), null));
        assertNull(r.error(), r.error());
        assertTrue(r.fatigue());
        assertTrue(r.recipe().contains("\nsleep 50\n"), r.recipe());
        assertTrue(r.recipe().contains("\nitem 1263 1 1"), r.recipe());
    }

    @Test
    void theSleepLevelIsAPercentage() {
        BobMapCampGenerator.Request req = new BobMapCampGenerator.Request(
                nearest("Cow", 100, 610, 1), List.of(), List.of(), null, 0, 1, List.of(), List.of(), null);
        assertEquals(
                "sleep is 1 to 100",
                BobMapCampGenerator.compile(pathfinder, req).error());
    }

    @Test
    void slotsCountAStackOnceAndEveryOtherItem() {
        List<BobMapCampGenerator.Item> items = List.of(
                new BobMapCampGenerator.Item(373, 1, 10, false),
                new BobMapCampGenerator.Item(10, 1, 5000, false),
                new BobMapCampGenerator.Item(13, 1, 1, false),
                new BobMapCampGenerator.Item(404, 1, 1, true));
        int worn = env.world().rules().equipmentTab() ? 0 : 1;
        assertEquals(10 + 1 + 1 + worn, BobMapCampGenerator.slots(env, items));
    }

    @Test
    void anItemTheWorldLacksIsRefused() {
        int max = env.world().rules().maxItemId();
        assumeTrue(max != ServerRules.UNRESTRICTED && max < 1423);
        BobMapCampGenerator.Request req = new BobMapCampGenerator.Request(
                nearest("Cow", 100, 610, 1),
                List.of(new BobMapCampGenerator.Item(1423, 1, 1, true)),
                List.of(),
                null,
                null,
                1,
                List.of(),
                List.of(),
                null);
        assertEquals(
                "Rune Chain Mail Legs does not exist on uranium",
                BobMapCampGenerator.compile(pathfinder, req).error());
    }

    @Test
    void theErrorNamesWhatTheWaterfallDungeonNeedsAndTheRecipeTakesIt() {
        List<Integer> giants = List.of(2702, 2703, 2706, 2707);
        BobMapCampGenerator.Item knife = new BobMapCampGenerator.Item(13, 1, 1, false);
        BobMapCampGenerator.Response bare =
                BobMapCampGenerator.compile(pathfinder, giantsCarrying(giants, List.of(knife)));
        assertNotNull(bare.error());
        assertTrue(
                bare.error().contains("wearing Glarial's amulet")
                        && bare.error().contains("Rope"),
                bare.error());

        BobMapCampGenerator.Response kitted = BobMapCampGenerator.compile(
                pathfinder,
                giantsCarrying(
                        giants,
                        List.of(
                                knife,
                                new BobMapCampGenerator.Item(237, 1, 1, false),
                                new BobMapCampGenerator.Item(782, 1, 1, true))));
        assertNull(kitted.error(), kitted.error());
        assertTrue(kitted.recipe().contains("useobj 662 463 237 662 467"), kitted.recipe());
        assertTrue(
                kitted.warnings().stream().anyMatch(w -> w.endsWith("can hit for 10")),
                kitted.warnings().toString());
        assertEquals(10, kitted.stepHits());
        String block = kitted.recipe()
                .lines()
                .filter(l -> l.startsWith("block "))
                .findFirst()
                .orElseThrow();
        assertTrue(block.contains("Fire Giant") && block.contains("Shadow spider"), block);
    }

    private static BobMapCampGenerator.Request giantsCarrying(
            List<Integer> giants, List<BobMapCampGenerator.Item> items) {
        return new BobMapCampGenerator.Request(giants, items, List.of(), null, null, 1, List.of(), List.of(), null);
    }

    @Test
    void theJsonRoundTrips() {
        List<Integer> cows = nearest("Cow", 100, 610, 2);
        String json = "{\"targets\":[" + cows.get(0) + "," + cows.get(1) + "],"
                + "\"items\":[{\"id\":373,\"min\":1,\"target\":10}],\"food\":[373],\"eat\":7,\"loot\":[20],"
                + "\"bury\":[20],\"style\":1}";
        BobMapCampGenerator.Request req = PlannerJson.campRequest(json);
        assertEquals(cows, req.targets());
        assertEquals(List.of(new BobMapCampGenerator.Item(373, 1, 10, false)), req.items());
        String out = PlannerJson.write(BobMapCampGenerator.compile(pathfinder, req));
        assertTrue(out.contains("\"recipe\":\"# "), out);
        assertTrue(out.contains("item 373 1 10"), out);
        assertTrue(out.contains("bury 20"), out);
        assertTrue(out.contains("\"toBank\":[{"), out);
    }

    private static BobMapCampGenerator.Response compile(List<Integer> targets) {
        return BobMapCampGenerator.compile(
                pathfinder,
                new BobMapCampGenerator.Request(
                        targets,
                        List.of(new BobMapCampGenerator.Item(373, 1, 10, false)),
                        List.of(373),
                        7,
                        null,
                        1,
                        List.of(20),
                        List.of(20),
                        null));
    }

    private static int spawnId(String name) {
        for (ServerData.NpcSpawn s : env.npcSpawns()) {
            NpcDef d = env.npc(s.id());
            if (d != null && d.name().equalsIgnoreCase(name)) {
                return s.id();
            }
        }
        throw new AssertionError("no " + name);
    }

    // The n spawns of `name` nearest (x, z).
    private static List<Integer> nearest(String name, int x, int z, int n) {
        List<ServerData.NpcSpawn> all = new ArrayList<>();
        for (ServerData.NpcSpawn s : env.npcSpawns()) {
            NpcDef d = env.npc(s.id());
            if (d != null && d.name().equalsIgnoreCase(name)) {
                all.add(s);
            }
        }
        all.sort(Comparator.comparingInt(s -> Math.max(Math.abs(s.startX() - x), Math.abs(s.startZ() - z))));
        assertTrue(all.size() >= n, "spawns of " + name + ": " + all.size());
        return all.subList(0, n).stream().map(ServerData.NpcSpawn::serverIndex).toList();
    }
}
