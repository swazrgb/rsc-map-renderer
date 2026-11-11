package openrsc.bot.api;

import java.util.LinkedHashMap;
import java.util.Map;
import openrsc.bot.api.Shop.ShopNpc;
import org.jetbrains.annotations.Nullable;

public final class MutableShop {

    private boolean open;
    private final Map<Integer, Integer> stock = new LinkedHashMap<>();
    private final Map<Integer, Integer> baseStock = new LinkedHashMap<>();
    private boolean generalStore;
    private int sellModifier;
    private int buyModifier;
    private int stockSensitivity;
    private @Nullable ShopNpc npc;
    private boolean npcFromServerChat;
    private Shop snap = Shop.EMPTY;

    public void opened(@Nullable ShopNpc keeper, boolean fromServerChat) {
        snap = null;
        open = true;
        npc = keeper;
        npcFromServerChat = fromServerChat;
    }

    public void opened() {
        opened(null, false);
    }

    public void header(boolean general, int sell, int buy, int sensitivity) {
        snap = null;
        generalStore = general;
        sellModifier = sell;
        buyModifier = buy;
        stockSensitivity = sensitivity;
    }

    public void closed() {
        open = false;
        stock.clear();
        baseStock.clear();
        npc = null;
        npcFromServerChat = false;
        generalStore = false;
        sellModifier = 0;
        buyModifier = 0;
        stockSensitivity = 0;
        snap = Shop.EMPTY;
    }

    public void stockSet(int catalogId, int amount, int baseAmount) {
        snap = null;
        stock.put(catalogId, Math.max(0, amount));
        baseStock.put(catalogId, baseAmount);
    }

    public void stockSet(int catalogId, int amount) {
        stockSet(catalogId, amount, amount);
    }

    public void cleared() {
        snap = null;
        stock.clear();
        baseStock.clear();
    }

    public boolean open() {
        return open;
    }

    public Shop snapshot() {
        Shop cached = snap;
        if (cached == null) {
            cached = new Shop(
                    open,
                    Map.copyOf(stock),
                    Map.copyOf(baseStock),
                    generalStore,
                    sellModifier,
                    buyModifier,
                    stockSensitivity,
                    npc,
                    npcFromServerChat);
            snap = cached;
        }
        return cached;
    }
}
