package openrsc.nav.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PathfinderInputsTest {

    private static PathfinderInputs.Response inputs;
    private static Pathfinder pathfinder;

    @BeforeAll
    static void build() {
        pathfinder = RealDefs.pathfinder();
        inputs = PathfinderInputs.of(pathfinder);
    }

    @Test
    void totalsCountTheRegistryItDescribes() {
        assertEquals(pathfinder.transports().size(), inputs.totals().transports());
        assertTrue(inputs.totals().gated() > 1000, "most transports are gated: " + inputs.totals());
        assertTrue(inputs.totals().gates() > 100, "distinct gate trees: " + inputs.totals());
        assertTrue(inputs.totals().facts() > 200, "distinct reads: " + inputs.totals());
    }

    @Test
    void aSkillCarriesTheLevelsItsGatesTestAndNamesWhatTheyOpen() {
        PathfinderInputs.SkillInput agility = skill("AGILITY");
        assertTrue(agility.breakpoints().size() > 3, "agility gates at several levels");
        for (PathfinderInputs.Threshold t : agility.breakpoints()) {
            assertTrue(t.transports() > 0, "a breakpoint some gate tests: " + t.at());
            assertFalse(t.opens().isEmpty(), "and it names what it opens: " + t.at());
        }
        assertTrue(inputs.combatSkills().contains("ATTACK"));
        assertTrue(skill("ATTACK").combat());
        assertTrue(skill("ATTACK").breakpoints().isEmpty(), "no gate tests attack");
        assertTrue(
                inputs.skills().stream().noneMatch(s -> s.skill().equals("FIREMAKING")),
                "firemaking changes no route, so it is not asked for");
    }

    @Test
    void skillsComeInTheGamesOwnOrder() {
        List<String> inGameOrder = List.of(
                "ATTACK",
                "DEFENSE",
                "STRENGTH",
                "HITS",
                "RANGED",
                "PRAYER",
                "MAGIC",
                "COOKING",
                "WOODCUTTING",
                "FLETCHING",
                "FISHING",
                "FIREMAKING",
                "CRAFTING",
                "SMITHING",
                "MINING",
                "HERBLAW",
                "AGILITY",
                "THIEVING");
        List<String> published =
                inputs.skills().stream().map(PathfinderInputs.SkillInput::skill).toList();
        assertEquals(
                inGameOrder.stream().filter(published::contains).toList(),
                published,
                "published in the stats screen's order, minus the skills nothing reads");

        assertFalse(published.contains("FLETCHING"));
        assertFalse(published.contains("SMITHING"));
        assertTrue(published.contains("ATTACK"), "carried for the combat level, though ungated");
    }

    @Test
    void aFamilyNamesItsTiersAndEachTierIsAnItemOfItsOwn() {
        PathfinderInputs.ItemGroup pickaxe = inputs.itemGroups().stream()
                .filter(g -> g.label().equals("a pickaxe"))
                .findFirst()
                .orElseThrow();
        assertEquals(6, pickaxe.items().size(), "every tier is a member");
        assertTrue(pickaxe.transports() > 0);
        for (PathfinderInputs.ItemRef member : pickaxe.items()) {
            PathfinderInputs.ItemInput own = inputs.items().stream()
                    .filter(i -> i.id() == member.id())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("a family member is an item too: " + member.name()));
            assertTrue(
                    own.carry().stream().anyMatch(t -> t.at() == 1 && t.transports() > 0),
                    "and opens what the family opens: " + member.name());
        }
        assertTrue(
                inputs.items().stream().anyMatch(i -> i.id() == ItemId.KNIFE.id()),
                "the knife of a knife or a melee weapon is an item too");
    }

    @Test
    void anItemCarriesTheCountsGatesAskForAndTheOnesTheyRefuse() {
        PathfinderInputs.ItemInput coins = inputs.items().stream()
                .filter(i -> i.id() == ItemId.COINS.id())
                .findFirst()
                .orElseThrow();
        List<Integer> asked =
                coins.carry().stream().map(PathfinderInputs.Threshold::at).toList();
        assertTrue(asked.contains(10), "the Al Kharid toll / Shantay pass price: " + asked);
        assertTrue(asked.contains(30), "the charter fare: " + asked);
        assertFalse(coins.mustNotCarry().isEmpty(), "the banana-plantation escape is only for a bot under the fare");
    }

    @Test
    void aQuestCarriesTheStagesThatMatter() {
        PathfinderInputs.QuestInput seaSlug = inputs.quests().stream()
                .filter(q -> q.id() == QuestId.SEA_SLUG.id())
                .findFirst()
                .orElseThrow();
        assertTrue(seaSlug.atLeast().stream().anyMatch(t -> t.at() == 5), "the pier ladder changes at stage 5");
        assertNotNull(seaSlug.done(), "and the fishing-platform boat wants it done");
    }

    @Test
    void theRestOfTheAskIsPublishedToo() {
        Optional<PathfinderInputs.CacheInput> gang = inputs.cache().stream()
                .filter(c -> c.key().equals("arrav_gang"))
                .findFirst();
        assertTrue(gang.isPresent(), "gates read the gang the player joined");
        assertEquals("int", gang.get().kind());
        assertEquals(4, inputs.policy().size(), "danger avoidance, aggressive NPCs, teleport spells, jewellery");
        assertTrue(inputs.derived().contains("combatLevel"), "computed, never submitted");
    }

    private static PathfinderInputs.SkillInput skill(String name) {
        return inputs.skills().stream()
                .filter(s -> s.skill().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
