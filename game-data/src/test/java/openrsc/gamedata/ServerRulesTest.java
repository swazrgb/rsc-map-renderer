package openrsc.gamedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import openrsc.gamedata.ServerRules.NpcBlocking;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcDefs;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ServerRulesTest {

    private static ServerConf conf() {
        try {
            return ServerConf.resolve();
        } catch (RuntimeException e) {
            Assumptions.abort("server conf tree not available");
            return null;
        }
    }

    private static ServerRules rules(String world) throws IOException {
        return WorldProfile.fromConf(conf(), world).rules();
    }

    @Test
    void uraniumAndColeslawDifferWhereTheBotPlaysDifferently() throws IOException {
        ServerRules uranium = rules("uranium");
        ServerRules coleslaw = rules("rsccoleslaw");

        assertEquals(NpcBlocking.AGGRESSIVE, uranium.npcBlocking());
        assertEquals(NpcBlocking.NONE, coleslaw.npcBlocking());

        assertTrue(uranium.fatigue());
        assertFalse(coleslaw.fatigue());

        assertFalse(uranium.equipmentTab());
        assertTrue(coleslaw.equipmentTab());

        assertFalse(uranium.batching());
        assertTrue(coleslaw.batching());

        assertEquals(ServerRules.MEMBERS_BANK_CAPACITY, uranium.bankCapacity());
        assertEquals(ServerRules.CUSTOM_BANK_CAPACITY, coleslaw.bankCapacity());

        assertEquals(2, uranium.maxPvpMeleeDistance());
        assertEquals(3, coleslaw.maxPvpMeleeDistance());

        assertEquals(640, uranium.gameTickMs());
        assertEquals(430, coleslaw.gameTickMs());

        assertEquals(1.0, uranium.npcRespawnMultiplier());
        assertEquals(0.7, coleslaw.npcRespawnMultiplier());

        assertEquals(1289, uranium.maxItemId());
        assertEquals(ServerRules.UNRESTRICTED, coleslaw.maxItemId());

        assertEquals(1.0, uranium.skillingExpRate());
        assertEquals(2.0, coleslaw.skillingExpRate());

        assertTrue(coleslaw.customBanks());
        assertFalse(uranium.customBanks());

        assertTrue(coleslaw.quickBank());
        assertTrue(coleslaw.bankNotes());
        assertTrue(coleslaw.dropX());
        assertTrue(coleslaw.quickTrade());
        assertTrue(coleslaw.postQuestRegionsOpen());
        assertFalse(uranium.quickBank());
        assertFalse(uranium.bankNotes());
        assertFalse(uranium.dropX());
        assertFalse(uranium.quickTrade());
        assertFalse(uranium.postQuestRegionsOpen());
    }

    @Test
    void authenticCarriesUraniumsRules() throws IOException {
        assertEquals(rules("uranium"), WorldProfile.authentic().rules());
        assertSame(ServerRules.uranium(), WorldProfile.authentic().rules());
    }

    @Test
    void wieldRulesPerWorld() throws IOException {
        for (String world : new String[] {"uranium", "rsccoleslaw"}) {
            ServerRules r = rules(world);
            assertFalse(r.noLevelRequirementWield(), world);
            assertFalse(r.equipQuestItemsWithoutQuests(), world);
            assertFalse(r.strictCheckAll(), world);
            assertFalse(r.strictPoisonedDartCheck(), world);
            assertFalse(r.strictPoisonedKnifeCheck(), world);
            assertFalse(r.strictPoisonedSpearCheck(), world);
        }
        assertFalse(rules("uranium").formFittingChainmail());
        assertTrue(rules("rsccoleslaw").formFittingChainmail());
        assertFalse(rules("uranium").combatOdyssey());
        assertTrue(rules("rsccoleslaw").combatOdyssey());

        ServerRules none = parse();
        assertTrue(none.strictCheckAll() && none.strictPoisonedDartCheck());
        assertTrue(none.strictPoisonedKnifeCheck() && none.strictPoisonedSpearCheck());
        assertFalse(parse("form_fitting_chainmail: true").formFittingChainmail(), "only with custom_sprites");
    }

    @Test
    void kbdRareDropTablePerWorld() throws IOException {
        assertFalse(rules("uranium").kbdRareDropTable());
        assertTrue(rules("rsccoleslaw").kbdRareDropTable());
        assertFalse(parse().kbdRareDropTable());
        assertFalse(parse("custom_sprites: true").kbdRareDropTable(), "the table is built with custom quests");
        assertTrue(parse("custom_sprites: true", "want_custom_quests: true").kbdRareDropTable());
        assertFalse(
                parse("custom_sprites: true", "want_custom_quests: true", "want_openpk_points: true")
                        .kbdRareDropTable(),
                "OpenPK rolls its own tables instead");
    }

    @Test
    void bankCapacityFollowsTheServersDerivation() {
        assertEquals(
                ServerRules.F2P_BANK_CAPACITY,
                parse("member_world: false", "want_custom_banks: true").bankCapacity());
        assertEquals(
                ServerRules.MEMBERS_BANK_CAPACITY, parse("member_world: true").bankCapacity());
        assertEquals(
                ServerRules.CUSTOM_BANK_CAPACITY,
                parse("want_custom_banks: true").bankCapacity());
    }

    @Test
    void anNpcBlockingModeTheServerDoesNotKnowBlocksNothing() {
        assertEquals(NpcBlocking.ALL, parse("npc_blocking: 4").npcBlocking());
        assertEquals(NpcBlocking.NONE, parse("npc_blocking: 7").npcBlocking());
    }

    @Test
    void eachNpcBlockingModeBlocksWhatTheServerBlocks() throws IOException {
        ServerConf conf = conf();
        NpcDefs defs =
                NpcDefs.load(conf.defs().resolve("NpcDefs.json"), conf.defs().resolve("NpcDefsCustom.json"));
        NpcDef mugger = defs.get(NpcId.MUGGER.id());
        NpcDef unicorn = defs.get(NpcId.UNICORN.id());
        NpcDef banker = defs.get(NpcId.BANKER.id());
        assertTrue(mugger.aggressive() && mugger.attackable(), "premise: the mugger is aggressive");
        assertTrue(unicorn.attackable() && !unicorn.aggressive(), "premise: the unicorn is passive");
        assertFalse(banker.attackable() || banker.aggressive(), "premise: the banker is neither");
        int below = mugger.combatLevel() * 2;
        int above = mugger.combatLevel() * 2 + 1;

        for (NpcDef d : new NpcDef[] {mugger, unicorn, banker, null}) {
            assertFalse(NpcBlocking.NONE.blocks(d, below), "Coleslaw's 0: nothing blocks");
        }

        assertTrue(NpcBlocking.AGGRESSIVE_TO_LEVEL.blocks(mugger, below));
        assertFalse(NpcBlocking.AGGRESSIVE_TO_LEVEL.blocks(mugger, above));
        assertTrue(NpcBlocking.AGGRESSIVE_TO_LEVEL.blocks(mugger, 0), "an unknown level is below it");
        assertFalse(NpcBlocking.AGGRESSIVE_TO_LEVEL.blocks(unicorn, 3));

        assertTrue(NpcBlocking.AGGRESSIVE.blocks(mugger, above), "Uranium's 2: level plays no part");
        assertFalse(NpcBlocking.AGGRESSIVE.blocks(unicorn, 3));
        assertFalse(NpcBlocking.AGGRESSIVE.blocks(banker, 3));
        assertFalse(NpcBlocking.AGGRESSIVE.blocks(null, 3));

        assertTrue(NpcBlocking.ATTACKABLE.blocks(mugger, above));
        assertTrue(NpcBlocking.ATTACKABLE.blocks(unicorn, above));
        assertFalse(NpcBlocking.ATTACKABLE.blocks(banker, 3));

        for (NpcDef d : new NpcDef[] {mugger, unicorn, banker, null}) {
            assertTrue(NpcBlocking.ALL.blocks(d, above), "4: every NPC blocks");
        }
    }

    @Test
    void everyNegativeCeilingIsNone() {
        assertEquals(ServerRules.UNRESTRICTED, parse("restrict_item_id: -5").maxItemId());
        assertEquals(ServerRules.UNRESTRICTED, parse("restrict_scenery_id: -2").maxSceneryId());
    }

    private static ServerRules parse(String... lines) {
        return ServerRules.from(ServerProperties.parse(List.of(lines)));
    }
}
