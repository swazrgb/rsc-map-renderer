package openrsc.nav.eligibility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import org.junit.jupiter.api.Test;

class KitTest {

    private static final int[] PICKAXE_TIERS = {
        ItemId.RUNE_PICKAXE.id(), ItemId.ADAMANTITE_PICKAXE.id(), ItemId.MITHRIL_PICKAXE.id(),
        ItemId.STEEL_PICKAXE.id(), ItemId.IRON_PICKAXE.id(), ItemId.BRONZE_PICKAXE.id()
    };

    private static Prereq rawAnyPickaxe() {
        Prereq[] tiers = new Prereq[PICKAXE_TIERS.length];
        for (int i = 0; i < tiers.length; i++) {
            tiers[i] = Prereq.fact(Fact.carriedAtLeast(PICKAXE_TIERS[i], 1));
        }
        return Prereq.any(tiers);
    }

    @Test
    void aNamedFamilyReadsAsItsNameAndDeclaresEveryMember() {
        assertEquals("a pickaxe", Kit.PICKAXE.description());
        assertTrue(Kit.PICKAXE.isNamed());
        assertArrayEquals(
                sorted(PICKAXE_TIERS),
                Kit.PICKAXE.inputs().itemIds(),
                "every tier is declared, so the eligibility class keys on all of them");
    }

    @Test
    void namingChangesNothingAboutWhoPasses() {
        Prereq raw = rawAnyPickaxe();
        assertTrue(Kit.PICKAXE.testFacts(holding(ItemId.BRONZE_PICKAXE.id())));
        assertTrue(raw.testFacts(holding(ItemId.BRONZE_PICKAXE.id())));
        assertFalse(Kit.PICKAXE.testFacts(holding()));
        assertFalse(raw.testFacts(holding()));

        Map<Fact, Integer> numbers = new HashMap<>();
        int[] named = Kit.PICKAXE.compile(f -> numbers.computeIfAbsent(f, k -> numbers.size()));
        int[] spelledOut = raw.compile(f -> numbers.computeIfAbsent(f, k -> numbers.size()));
        assertArrayEquals(spelledOut, named, "a name compiles to its child's program");
    }

    @Test
    void oldBridgeBowFamilyMirrorsTheServerNameCheck() {
        assertEquals("a bow", Kit.BOW_GATE.description());
        assertTrue(Kit.BOW_GATE.testFacts(holding(ItemId.MAGIC_LONGBOW.id())));
        assertTrue(Kit.BOW_GATE.testFacts(holding(ItemId.UNSTRUNG_SHORTBOW.id())));
        assertTrue(Kit.BOW_GATE.testFacts(holding(ItemId.CROSSBOW.id())));
        assertFalse(Kit.BOW_GATE.testFacts(holding(ItemId.BRONZE_ARROWS.id())));
    }

    @Test
    void aFamilyIsOneRegistryEntryAndComposesUnderItsName() {
        assertSame(Kit.PICKAXE, Prereq.named("a pickaxe", rawAnyPickaxe()), "interned per (label, gate)");

        Prereq boulder = Prereq.all(Prereq.fact(Fact.skillAtLeast(Skill.MINING, 52)), Kit.PICKAXE);
        assertEquals("mining ≥ 52 and a pickaxe", boulder.description());
        assertEquals(List.of(Kit.PICKAXE), boulder.namedParts());
    }

    @Test
    void theRegistryPublishesItsFamiliesAndSkillRungs() {
        RealDefs.serverData();

        Pathfinder pf = RealDefs.pathfinder();

        Pathfinder.ItemGroup pickaxe = pf.itemGroups().stream()
                .filter(g -> g.label().equals("a pickaxe"))
                .findFirst()
                .orElseThrow();
        assertArrayEquals(sorted(PICKAXE_TIERS), pickaxe.itemIds());
        assertTrue(pickaxe.transports() > 0, "some gate asks for a pickaxe");
        assertTrue(pf.itemGroups().size() >= 4, "the light, axe and cutter families are published too");

        SkillBreakpoints rungs = pf.skillBreakpoints();
        assertTrue(rungs.current(Skill.AGILITY).length > 3);
        assertEquals(0, rungs.current(Skill.FIREMAKING).length, "no gate reads firemaking");
    }

    private static java.util.function.Predicate<Fact> holding(int... carried) {
        return f -> {
            for (int id : carried) {
                if (f.kind() == Fact.Kind.CARRIED_AT_LEAST && f.itemId() == id) {
                    return true;
                }
            }
            return false;
        };
    }

    private static int[] sorted(int[] ids) {
        int[] out = ids.clone();
        java.util.Arrays.sort(out);
        return out;
    }
}
