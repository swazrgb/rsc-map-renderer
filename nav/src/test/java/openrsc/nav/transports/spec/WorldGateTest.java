package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.byWorld;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.WorldGate.feature;
import static openrsc.nav.transports.spec.WorldGate.item;
import static openrsc.nav.transports.spec.WorldGate.locationData;
import static openrsc.nav.transports.spec.WorldGate.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.WorldProfile.Feature;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.RealDefs;
import openrsc.nav.transports.NpcRoutes;
import org.junit.jupiter.api.Test;

class WorldGateTest {

    // Two mechanics that expand on every world: only a gate keeps them off one.
    private static final TransportSpec TALK = NpcRoutes.GLIDER_TO_VARROCK.spec();

    private static final TransportSpec OBJECT = ObjectTeleport.object(349, 180, 129, 621, 596, 30);

    @Test
    void aFeatureGateDeclaresOnlyWithTheSwitchOn() {
        ServerData uranium = RealDefs.serverData();
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        for (TransportSpec spec : new TransportSpec[] {TALK, OBJECT}) {
            assertFalse(spec.expand(uranium, "X").isEmpty(), "ungated, the row is on Uranium: " + spec);
            TransportSpec gated = onlyIn(feature(Feature.CUSTOM_LANDSCAPE), spec);
            assertTrue(gated.expand(uranium, "X").isEmpty(), "gated off Uranium: " + spec);
            assertEquals(
                    spec.expand(coleslaw, "X").size(),
                    gated.expand(coleslaw, "X").size(),
                    "and on Coleslaw: " + spec);
        }
    }

    @Test
    void aRuleGateSplitsTheWorlds() {
        ServerData uranium = RealDefs.serverData();
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        WorldGate open = rule(ServerRules::postQuestRegionsOpen);
        assertTrue(onlyIn(open, TALK).expand(uranium, "X").isEmpty(), "Uranium locks post-quest regions");
        assertFalse(onlyIn(open, TALK).expand(coleslaw, "X").isEmpty(), "Coleslaw keeps them open");
    }

    @Test
    void anItemGateFollowsTheWorldsItemCeiling() {
        ServerData uranium = RealDefs.serverData();
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        int cape = ItemId.CRAFTING_CAPE.id();
        assertFalse(item(cape).admits(uranium.world()), "Uranium's restrict_item_id 1289 keeps capes out");
        assertTrue(item(cape).admits(coleslaw.world()), "Coleslaw has no ceiling");
        assertTrue(item(ItemId.COINS.id()).admits(uranium.world()), "an authentic item is on Uranium");
    }

    @Test
    void aLocationDataGateFollowsTheWorldsLocsSet() {
        ServerData uranium = RealDefs.serverData();
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        assertFalse(locationData(2).admits(uranium.world()), "Uranium loads location_data 1");
        assertTrue(locationData(2).admits(coleslaw.world()), "Coleslaw loads the *Other locs too");
    }

    @Test
    void aWorldPickedRowIsOneOfItsTwoDeclarationsOnEachWorld() {
        ServerData uranium = RealDefs.serverData();
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        TransportSpec picked = byWorld(locationData(2), TALK, OBJECT);
        assertEquals(
                OBJECT.expand(uranium, "X").get(0).entries(),
                picked.expand(uranium, "X").get(0).entries(),
                "elsewhere: the second");
        assertEquals(
                TALK.expand(coleslaw, "X").get(0).entries(),
                picked.expand(coleslaw, "X").get(0).entries(),
                "where admitted: the first");
    }
}
