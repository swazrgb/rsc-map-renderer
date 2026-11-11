package openrsc.livemap.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import openrsc.bot.api.eligibility.Holding;
import openrsc.livemap.stream.TopicHub;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

class ItemTopicsTest {

    private static final int LOBSTER = 373;
    private static final int BONES = 20;

    /** Alice is shown to everyone; Bob only to logged-in viewers; Carol holds no lobsters but is gaining them. */
    private static final ItemHoldings HOLDINGS = new ItemHoldings() {
        @Override
        public Map<Integer, List<Holding>> byItem() {
            return Map.of(
                    LOBSTER,
                    List.of(new Holding("bob", LOBSTER, 0, 300, false), new Holding("alice", LOBSTER, 5, 20, false)),
                    BONES,
                    List.of(new Holding("bob", BONES, 1, 0, false)));
        }

        @Override
        public List<Holding> holdersOf(int itemId) {
            return byItem().getOrDefault(itemId, List.of());
        }

        @Override
        public Predicate<String> visible(boolean authed) {
            return u -> authed || u.equals("alice");
        }

        @Override
        public Map<Integer, Double> rates() {
            return Map.of(LOBSTER, 1000.0);
        }

        @Override
        public Map<String, Map<Integer, Double>> ratesByBot() {
            return Map.of("alice", Map.of(LOBSTER, 100.0), "carol", Map.of(LOBSTER, 900.0));
        }
    };

    private static ItemTopics topics() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("holdings", HOLDINGS));
        return new ItemTopics(
                beans.getBeanProvider(ItemHoldings.class),
                RealDefs.serverData(),
                new TopicHub(JsonMapper.builder().build()));
    }

    @Test
    void aLoggedInViewerSeesEveryHolderAndEveryBotsRate() {
        List<ItemAggregateDto> owned = topics().owned(true);
        assertEquals(
                List.of(LOBSTER, BONES),
                owned.stream().map(ItemAggregateDto::id).toList(),
                "most held first");
        ItemAggregateDto lobsters = owned.get(0);
        assertEquals(325, lobsters.total());
        assertEquals(5, lobsters.inInventory());
        assertEquals(1000.0, lobsters.perHour());
        assertEquals("Lobster", lobsters.name());
    }

    @Test
    void anAnonymousViewerSeesOnlyTheBotsShownToIt() {
        List<ItemAggregateDto> owned = topics().owned(false);
        assertEquals(List.of(LOBSTER), owned.stream().map(ItemAggregateDto::id).toList());
        assertEquals(25, owned.get(0).total());
        assertEquals(100.0, owned.get(0).perHour(), "the rates of the bots it sees, not every bot's");
    }

    @Test
    void anItemsHoldersIncludeTheBotsGainingItThatHoldNoneYet() {
        ItemAggregateDto authed = topics().item(LOBSTER, true);
        assertEquals(
                List.of("bob", "alice", "carol"),
                authed.holders().stream().map(ItemHolderDto::username).toList());
        assertEquals(900.0, authed.holders().get(2).perHour());

        ItemAggregateDto anon = topics().item(LOBSTER, false);
        assertEquals(
                List.of("alice"),
                anon.holders().stream().map(ItemHolderDto::username).toList());
    }
}
