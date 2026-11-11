package openrsc.bot.api;

import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record Shop(
        boolean open,
        Map<Integer, Integer> stock,
        Map<Integer, Integer> baseStock,
        boolean generalStore,
        int sellModifier,
        int buyModifier,
        int stockSensitivity,
        @Nullable ShopNpc npc,
        boolean npcFromServerChat) {

    public record ShopNpc(int serverIndex, int npcId, int x, int z, long tick) {}

    public static final Shop EMPTY = new Shop(false, Map.of(), Map.of(), false, 0, 0, 0, null, false);

    public int amount(int catalogId) {
        Integer n = stock.get(catalogId);
        return n == null ? 0 : n;
    }

    public int baseAmount(int catalogId) {
        Integer n = baseStock.get(catalogId);
        return n == null ? 0 : n;
    }

    public boolean sells(int catalogId) {
        return stock.containsKey(catalogId);
    }

    public Set<Integer> catalog() {
        return stock.keySet();
    }
}
