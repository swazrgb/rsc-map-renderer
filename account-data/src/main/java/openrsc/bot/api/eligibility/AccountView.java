package openrsc.bot.api.eligibility;

import java.util.List;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Holdings;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;

/**
 * An {@link AccountFacts} with some reads replaced: each projection that is not {@code null}
 * replaces the base's, and every other read is the base's own.
 */
final class AccountView implements AccountFacts {

    private final AccountFacts base;
    private final Inventory inventory;
    private final Point pos;
    private final Quests quests;
    private final Stats stats;
    private final Holdings owned;

    AccountView(AccountFacts base, Inventory inventory, Point pos, Quests quests) {
        this(base, inventory, pos, quests, null);
    }

    AccountView(AccountFacts base, Inventory inventory, Point pos, Quests quests, Stats stats) {
        this.base = base;
        this.inventory = inventory;
        this.pos = pos;
        this.quests = quests;
        this.stats = stats;
        this.owned = inventory != null ? new Holdings(inventory, base.bank()) : base.owned();
    }

    @Override
    public Inventory inventory() {
        return inventory != null ? inventory : base.inventory();
    }

    @Override
    public Point pos() {
        return pos != null ? pos : base.pos();
    }

    @Override
    public Holdings owned() {
        return owned;
    }

    @Override
    public ServerData serverData() {
        return base.serverData();
    }

    @Override
    public Bank bank() {
        return base.bank();
    }

    @Override
    public Stats stats() {
        return stats != null ? stats : base.stats();
    }

    @Override
    public Quests quests() {
        return quests != null ? quests : base.quests();
    }

    @Override
    public String username() {
        return base.username();
    }

    @Override
    public List<String> groups() {
        return base.groups();
    }

    @Override
    public boolean online() {
        return base.online();
    }

    @Override
    public CacheValues cacheValues() {
        return base.cacheValues();
    }
}
