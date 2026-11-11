package openrsc.livemap.items;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import openrsc.bot.api.eligibility.Holding;

/** The items the bots hold, as the application knows them, and how fast they come in where it keeps track. */
public interface ItemHoldings {

    /** Every bot's holdings by item id; each list holds a bot at most once. */
    Map<Integer, List<Holding>> byItem();

    /** The bots holding the item. */
    List<Holding> holdersOf(int itemId);

    /** The bots a viewer of that kind may see the holdings of. */
    Predicate<String> visible(boolean authed);

    /** Items gained per hour, summed over every bot, by item id; empty where no rates are kept. */
    default Map<Integer, Double> rates() {
        return Map.of();
    }

    /** Items gained per hour, by bot and then item id; empty where no rates are kept. */
    default Map<String, Map<Integer, Double>> ratesByBot() {
        return Map.of();
    }
}
