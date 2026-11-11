package openrsc.livemap.items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import openrsc.bot.api.eligibility.Holding;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.livemap.stream.Keyed;
import openrsc.livemap.stream.Parts;
import openrsc.livemap.stream.TopicHub;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The items topics, read from the application's {@link ItemHoldings}: {@code items/owned}, one row per item any bot a
 * viewer may see holds, most held first; and {@code items/item} ({@code id}), the item's holders, with the bots
 * gaining it that hold none yet. A logged-in viewer's per-hour figures are every bot's; an anonymous viewer's, only
 * those of the bots it may see. Nothing is registered when the application has no holdings.
 */
@Component
public class ItemTopics {

    private final @Nullable ItemHoldings holdings;
    private final ServerData data;

    public ItemTopics(ObjectProvider<ItemHoldings> holdings, ServerData data, TopicHub topics) {
        this.holdings = holdings.getIfAvailable();
        this.data = data;
        if (this.holdings == null) {
            return;
        }
        topics.polled(
                "items/owned",
                TopicHub.Access.ANYONE,
                TopicHub.BACKSTOP_MS,
                q -> Keyed.by(owned(q.authed()).stream().map(ItemRowDto::of).toList(), ItemRowDto::id));
        topics.polled(
                "items/item",
                TopicHub.Access.ANYONE,
                TopicHub.BACKSTOP_MS,
                List.of("id"),
                q -> Parts.of(item(q.intArg("id"), q.authed()))
                        .list("holders", (ItemHolderDto h) -> h.username())
                        .toMap());
    }

    private static double visiblePerHour(int id, List<Holding> holders, Map<String, Map<Integer, Double>> ratesByBot) {
        double sum = 0;
        for (Holding h : holders) {
            Map<Integer, Double> br = ratesByBot.get(h.username());
            if (br != null) {
                sum += br.getOrDefault(id, 0.0);
            }
        }
        return sum;
    }

    List<ItemAggregateDto> owned(boolean authed) {
        ItemHoldings source = Objects.requireNonNull(holdings);
        Predicate<String> vis = source.visible(authed);
        Map<Integer, List<Holding>> byItem = source.byItem();
        ItemDefs defs = data.itemDefs();
        Map<Integer, Double> rates = source.rates();
        Map<String, Map<Integer, Double>> ratesByBot = source.ratesByBot();
        List<ItemAggregateDto> out = new ArrayList<>(byItem.size());
        for (Map.Entry<Integer, List<Holding>> e : byItem.entrySet()) {
            int id = e.getKey();
            List<Holding> holders = authed
                    ? e.getValue()
                    : e.getValue().stream().filter(h -> vis.test(h.username())).toList();
            if (holders.isEmpty()) {
                continue;
            }
            double perHour = authed ? rates.getOrDefault(id, 0.0) : visiblePerHour(id, holders, ratesByBot);
            out.add(toDto(id, holders, defs == null ? null : defs.nameOf(id), perHour, ratesByBot));
        }
        out.sort(Comparator.comparingLong(ItemAggregateDto::total)
                .reversed()
                .thenComparing(ItemAggregateDto::name)
                .thenComparingInt(ItemAggregateDto::id));
        return out;
    }

    ItemAggregateDto item(int id, boolean authed) {
        ItemHoldings source = Objects.requireNonNull(holdings);
        Predicate<String> vis = source.visible(authed);
        List<Holding> holders = new ArrayList<>(source.holdersOf(id));
        Map<String, Map<Integer, Double>> ratesByBot = source.ratesByBot();
        Set<String> present = new HashSet<>();
        for (Holding h : holders) {
            present.add(h.username());
        }
        for (Map.Entry<String, Map<Integer, Double>> e : ratesByBot.entrySet()) {
            if (Math.abs(e.getValue().getOrDefault(id, 0.0)) >= 0.5 && present.add(e.getKey())) {
                holders.add(new Holding(e.getKey(), id, 0, 0, false));
            }
        }
        if (!authed) {
            holders.removeIf(h -> !vis.test(h.username()));
        }
        ItemDefs defs = data.itemDefs();
        double total = authed ? source.rates().getOrDefault(id, 0.0) : visiblePerHour(id, holders, ratesByBot);
        return toDto(id, holders, defs == null ? null : defs.nameOf(id), total, ratesByBot);
    }

    private static ItemAggregateDto toDto(
            int id,
            List<Holding> holders,
            @Nullable String name,
            double perHour,
            Map<String, Map<Integer, Double>> rateByBot) {
        long inInventory = 0;
        long inBank = 0;
        List<ItemHolderDto> rows = new ArrayList<>(holders.size());
        for (Holding h : holders) {
            inInventory += h.inventory();
            inBank += h.bank();
            Map<Integer, Double> br = rateByBot.get(h.username());
            double hr = br == null ? 0.0 : br.getOrDefault(id, 0.0);
            rows.add(new ItemHolderDto(h.username(), h.inventory(), h.bank(), h.equipped(), hr));
        }
        return new ItemAggregateDto(
                id, name == null ? "?" : name, inInventory + inBank, inInventory, inBank, perHour, rows);
    }
}
