package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.TransportRef;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.transports.SpecialDoor;
import openrsc.nav.transports.Webs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OverrideCostsTest {

    // Its own: the same-array assertions lean on this engine's kept overrides, which other classes'
    // policies must not push out between two asks.
    private static Engine engine;

    @BeforeAll
    static void load() {
        engine = (Engine)
                Pathfinder.load(RealDefs.serverData(), NavCaches.none()).nav();
    }

    @AfterAll
    static void release() {
        engine = null;
    }

    @Test
    void resolvedOverridesMatchTheLoopTheyReplaced() {
        List<Transport> transports = engine.graph().transports();
        SplittableRandom rnd = new SplittableRandom(11);
        for (int round = 0; round < 40; round++) {
            Map<String, Integer> overrides = new HashMap<>();
            int entries = 1 + rnd.nextInt(6);
            for (int k = 0; k < entries; k++) {
                Transport t = transports.get(rnd.nextInt(transports.size()));
                List<String> keys = new ArrayList<>();
                t.policyKeys().forEach(keys::addAll);
                for (int n = 1 + rnd.nextInt(3); n > 0; n--) {
                    String key = keys.get(rnd.nextInt(keys.size()));
                    int pick = rnd.nextInt(8);
                    overrides.put(
                            key, pick < 2 ? NavPolicy.PROHIBITIVE : pick == 2 ? NavPolicy.LIFTED : rnd.nextInt(500));
                }
            }
            // Both teleport kinds allowed, so no policy gate stands between a lift and its transport.
            NavPolicy policy = new NavPolicy(NavPolicy.DangerAvoidance.ON, true, true, Map.copyOf(overrides), false);
            boolean[] canUse = new boolean[transports.size()];
            for (int i = 0; i < canUse.length; i++) {
                canUse[i] = rnd.nextInt(10) < 7;
            }
            boolean[] expectedUse = canUse.clone();
            int[] expected = reference(transports, overrides, expectedUse);

            boolean[] firstUse = canUse.clone();
            int[] first = engine.costsFor(policy, firstUse);
            assertArrayEquals(expected, first, "costs, round " + round);
            assertArrayEquals(expectedUse, firstUse, "prohibitions, round " + round);

            boolean[] againUse = canUse.clone();
            int[] again = engine.costsFor(
                    new NavPolicy(NavPolicy.DangerAvoidance.OFF, true, true, new HashMap<>(overrides), false),
                    againUse);
            assertSame(first, again, "round " + round);
            assertArrayEquals(expectedUse, againUse, "prohibitions again, round " + round);
        }
    }

    @Test
    void aMapThatChangesNoCostPricesAtTheBaseCostsThemselves() {
        List<Transport> transports = engine.graph().transports();
        boolean[] open = new boolean[transports.size()];
        Arrays.fill(open, true);
        int[] base = engine.costsFor(NavPolicy.DEFAULT, open.clone());
        Transport door = transports.get(0);
        NavPolicy fence = new NavPolicy(
                NavPolicy.DangerAvoidance.ON, false, false, Map.of(door.name(), NavPolicy.PROHIBITIVE), false);
        boolean[] fenced = open.clone();
        assertSame(base, engine.costsFor(fence, fenced));
        assertFalse(fenced[0], "the prohibition still closes the transport it names");
        NavPolicy repriced =
                new NavPolicy(NavPolicy.DangerAvoidance.ON, false, false, Map.of(door.name(), base[0] + 7), false);
        assertNotSame(base, engine.costsFor(repriced, open.clone()), "a changed cost is its own array");
    }

    @Test
    void aPolicyAddressesAConstantOrOneArmOfIt() {
        List<Transport> transports = engine.graph().transports();
        int up = -1;
        int down = -1;
        for (int i = 0; i < transports.size(); i++) {
            String arm = transports.get(i).armKey();
            if (SpecialDoor.DOOR_MINING_GUILD.lowToHigh().key().equals(arm)) {
                up = i;
            } else if (SpecialDoor.DOOR_MINING_GUILD.highToLow().key().equals(arm)) {
                down = i;
            }
        }
        boolean[] open = new boolean[transports.size()];
        Arrays.fill(open, true);

        boolean[] fenced = open.clone();
        engine.costsFor(NavPolicy.DEFAULT.prohibit(SpecialDoor.DOOR_MINING_GUILD), fenced);
        assertFalse(fenced[up] || fenced[down], "the constant fences both arms");

        boolean[] oneWay = open.clone();
        int[] costs = engine.costsFor(
                NavPolicy.DEFAULT
                        .prohibit(SpecialDoor.DOOR_MINING_GUILD)
                        .cost(SpecialDoor.DOOR_MINING_GUILD.lowToHigh(), 500),
                oneWay);
        assertTrue(oneWay[up], "the arm's own price wins over the constant's prohibition");
        assertEquals(500, costs[up]);
        assertFalse(oneWay[down], "the other arm is still fenced");
    }

    @Test
    void anOverrideThatNamesNoTransportFails() {
        boolean[] open = new boolean[engine.graph().transports().size()];
        engine.costsFor(NavPolicy.DEFAULT.prohibit(SpecialDoor.TENT_DOOR_ANVIL.highToLow()), open.clone());
        NavPolicy typo = new NavPolicy(
                NavPolicy.DangerAvoidance.ON, false, false, Map.of("DOOR_MINNING_GUILD", NavPolicy.PROHIBITIVE), false);
        assertThrows(IllegalArgumentException.class, () -> engine.costsFor(typo, open.clone()));
    }

    @Test
    void aPlacementKeyAddressesEveryArmOfOneObject() {
        List<Transport> transports = engine.graph().transports();
        boolean[] open = new boolean[transports.size()];
        Arrays.fill(open, true);
        int[] costs = engine.costsFor(NavPolicy.DEFAULT.cost(new TransportRef.Arm("WEB@304_300"), 777), open.clone());
        int priced = 0;
        for (int i = 0; i < costs.length; i++) {
            Transport t = transports.get(i);
            if (t.name().startsWith("WEB@304_300#")) {
                assertEquals(777, costs[i], t.name());
                priced++;
            } else if (Webs.WEB.name().equals(t.costOverrideKey())) {
                assertEquals(t.cost(), costs[i], "another web keeps its price: " + t.name());
            }
        }
        assertEquals(4, priced, "the diagonal web at (304,300) opens four crossings");
    }

    @Test
    void aDerivedDoorsArmAddressesThatArmAtEveryPlacement() {
        List<Transport> transports = engine.graph().transports();
        boolean[] open = new boolean[transports.size()];
        Arrays.fill(open, true);
        int[] costs =
                engine.costsFor(NavPolicy.DEFAULT.cost(SpecialDoor.ODD_LOOKING_WALL.lowToHigh(), 777), open.clone());
        int priced = 0;
        for (int i = 0; i < costs.length; i++) {
            Transport t = transports.get(i);
            if (!SpecialDoor.ODD_LOOKING_WALL.name().equals(t.costOverrideKey())) {
                continue;
            }
            if (t.name().endsWith("#lowToHigh")) {
                assertEquals(777, costs[i], t.name());
                priced++;
            } else {
                assertEquals(t.cost(), costs[i], "the other direction keeps its price: " + t.name());
            }
        }
        assertEquals(7, priced, "one arm at each of the seven odd looking walls");
    }

    @Test
    void aPlacementPriceDoesNotReopenAProhibitedDirection() {
        List<Transport> transports = engine.graph().transports();
        boolean[] open = new boolean[transports.size()];
        Arrays.fill(open, true);
        boolean[] canUse = open.clone();
        int[] costs = engine.costsFor(
                NavPolicy.DEFAULT
                        .prohibit(SpecialDoor.ODD_LOOKING_WALL.lowToHigh())
                        .cost(new TransportRef.Arm("ODD_LOOKING_WALL@273_435"), 555),
                canUse);
        int seen = 0;
        for (int i = 0; i < costs.length; i++) {
            String name = transports.get(i).name();
            if (name.equals("ODD_LOOKING_WALL@273_435#lowToHigh")) {
                assertFalse(canUse[i], "prohibited everywhere, priced here: still prohibited");
                seen++;
            } else if (name.equals("ODD_LOOKING_WALL@273_435#highToLow")) {
                assertTrue(canUse[i], name);
                assertEquals(555, costs[i], "the other direction takes the placement's price");
                seen++;
            } else if (name.startsWith("ODD_LOOKING_WALL@") && name.endsWith("#lowToHigh")) {
                assertFalse(canUse[i], name);
            }
        }
        assertEquals(2, seen);
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.costsFor(
                        NavPolicy.DEFAULT.prohibit(new TransportRef.Arm("ODD_LOOKING_WALL#273_434")), open.clone()));
    }

    @Test
    void aMoreSpecificPriceReopensAnOuterProhibit() {
        List<Transport> transports = engine.graph().transports();
        boolean[] open = new boolean[transports.size()];
        Arrays.fill(open, true);

        boolean[] armUse = open.clone();
        int[] armCosts = engine.costsFor(
                NavPolicy.DEFAULT
                        .prohibit(SpecialDoor.ODD_LOOKING_WALL.lowToHigh())
                        .cost(new TransportRef.Arm("ODD_LOOKING_WALL@273_435#lowToHigh"), 444),
                armUse);
        int reopened = 0;
        for (int i = 0; i < transports.size(); i++) {
            String name = transports.get(i).name();
            if (name.equals("ODD_LOOKING_WALL@273_435#lowToHigh")) {
                assertTrue(armUse[i], "the arm's own price reopens it");
                assertEquals(444, armCosts[i]);
                reopened++;
            } else if (name.startsWith("ODD_LOOKING_WALL@") && name.endsWith("#lowToHigh")) {
                assertFalse(armUse[i], "every other placement's arm stays prohibited: " + name);
            }
        }
        assertEquals(1, reopened);

        boolean[] placementUse = open.clone();
        int[] placementCosts = engine.costsFor(
                NavPolicy.DEFAULT
                        .prohibit(SpecialDoor.ODD_LOOKING_WALL)
                        .cost(new TransportRef.Arm("ODD_LOOKING_WALL@273_435"), 333),
                placementUse);
        reopened = 0;
        for (int i = 0; i < transports.size(); i++) {
            String name = transports.get(i).name();
            if (name.startsWith("ODD_LOOKING_WALL@273_435#")) {
                assertTrue(placementUse[i], "the placement's price reopens both its arms: " + name);
                assertEquals(333, placementCosts[i], name);
                reopened++;
            } else if (name.startsWith("ODD_LOOKING_WALL@")) {
                assertFalse(placementUse[i], "every other placement stays prohibited: " + name);
            }
        }
        assertEquals(2, reopened);
    }

    @Test
    void aPlacementThisWorldLacksIsAllowedButATypoIsNot() {
        boolean[] open = new boolean[engine.graph().transports().size()];
        engine.costsFor(NavPolicy.DEFAULT.prohibit(new TransportRef.Arm("MAGIC_PORTAL_149@972_165")), open.clone());
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.costsFor(
                        NavPolicy.DEFAULT.prohibit(new TransportRef.Arm("MAGIC_PORTAL_150@972_165")), open.clone()));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.costsFor(
                        NavPolicy.DEFAULT.prohibit(new TransportRef.Arm("SHANTAY_PASS_INTO_DESERT@1_2")),
                        open.clone()));
    }

    private static int[] reference(List<Transport> transports, Map<String, Integer> overrides, boolean[] canUse) {
        int[] costs = new int[transports.size()];
        for (int i = 0; i < costs.length; i++) {
            Transport t = transports.get(i);
            costs[i] = Math.max(0, t.cost());
            Integer o = null;
            boolean fencedAnywhere = false;
            for (List<String> tier : t.policyKeys()) {
                for (String key : tier) {
                    Integer v = overrides.get(key);
                    fencedAnywhere |= v != null && v == NavPolicy.PROHIBITIVE;
                }
                if (o != null) {
                    continue;
                }
                for (String key : tier) {
                    Integer v = overrides.get(key);
                    if (v != null && (o == null || v == NavPolicy.PROHIBITIVE)) {
                        o = v;
                    }
                }
            }
            if (o != null) {
                if (o == NavPolicy.PROHIBITIVE || (o == NavPolicy.LIFTED && fencedAnywhere)) {
                    canUse[i] = false;
                } else if (o == NavPolicy.LIFTED) {
                    canUse[i] = true;
                } else {
                    costs[i] = o;
                }
            }
        }
        return Arrays.copyOf(costs, costs.length);
    }
}
