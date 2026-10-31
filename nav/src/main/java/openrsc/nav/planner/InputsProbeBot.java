package openrsc.nav.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Holdings;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.QuestPoints;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;

/**
 * An account described by its planning inputs alone: levels, pack, bank, quest stages and
 * player-cache values. It has no position; wrap it with {@link AccountFacts#at} to give it one.
 *
 * <p>Immutable once built. Its containers, stats and quests are built once and the same instances
 * returned on every call.
 */
public final class InputsProbeBot implements AccountFacts {

    @Override
    public ServerData serverData() {
        return serverData;
    }

    @Override
    public boolean online() {
        return true;
    }

    private final ServerData serverData;
    private final Map<Skill, Integer> skills;
    private final Map<Integer, Integer> items;
    private final Set<Integer> equipped;
    private final Map<Integer, Integer> bank;
    private final Map<Integer, Integer> quests;
    private final CacheValues cache;
    private final Holdings holdings;

    /**
     * Item maps are amount by item id, {@code quests} stage by quest id ({@code -1} complete). A
     * skill's current and base level are both its {@code skills} level, 99 when missing; a missing
     * quest is not started. {@code cache} holds the only player-cache values the account knows. An
     * id in {@code equipped} is worn only when {@code items} carries it.
     *
     * @throws IllegalArgumentException when the items need more inventory slots than a pack has
     */
    public InputsProbeBot(
            ServerData serverData,
            Map<Skill, Integer> skills,
            Map<Integer, Integer> items,
            Set<Integer> equipped,
            Map<Integer, Integer> bank,
            Map<Integer, Integer> quests,
            CacheValues cache) {
        this.serverData = serverData;
        this.skills = Map.copyOf(skills);
        this.items = Map.copyOf(items);
        this.equipped = Set.copyOf(equipped);
        this.bank = Map.copyOf(bank);
        this.quests = Map.copyOf(quests);
        this.cache = cache;
        // A stackable takes one slot and anything else a slot per unit, as the server adds them; a worn
        // item is worn once.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L128
        List<InventorySlot> inv = new ArrayList<>();
        this.items.forEach((id, amount) -> {
            if (amount <= 0) {
                return;
            }
            boolean worn = this.equipped.contains(id);
            if (serverData.isStackable(id)) {
                inv.add(new InventorySlot(inv.size(), id, amount, worn));
                return;
            }
            for (int i = 0; i < amount; i++) {
                inv.add(new InventorySlot(inv.size(), id, 1, worn && i == 0));
            }
        });
        Inventory carried = Inventory.of(inv, serverData.world().rules().equipmentTab());
        if (carried.usedSlots() > Inventory.CAPACITY) {
            throw new IllegalArgumentException("the pack described needs " + carried.usedSlots()
                    + " slots; an inventory has " + Inventory.CAPACITY);
        }
        List<BankSlot> banked = new ArrayList<>();
        this.bank.forEach((id, amount) -> {
            if (amount > 0) {
                banked.add(new BankSlot(banked.size(), id, amount));
            }
        });
        this.holdings = new Holdings(carried, Bank.of(banked));
    }

    private Stats stats;

    private Quests questSheet;

    @Override
    public Stats stats() {
        Stats s = stats;
        if (s == null) {
            MutableStats ms = new MutableStats();
            for (Skill sk : Skill.values()) {
                int level = skills.getOrDefault(sk, 99);
                ms.setSkill(sk.id, level, level, 0);
            }
            s = ms.snapshot();
            stats = s;
        }
        return s;
    }

    @Override
    public Quests quests() {
        Quests q = questSheet;
        if (q == null) {
            MutableQuests mq = new MutableQuests();
            quests.forEach(mq::setStage);
            mq.setQuestPoints(QuestPoints.total(id -> quests.getOrDefault(id, 0)));
            q = mq.snapshot();
            questSheet = q;
        }
        return q;
    }

    @Override
    public Inventory inventory() {
        return holdings.inventory();
    }

    @Override
    public Bank bank() {
        return holdings.bank();
    }

    @Override
    public Holdings owned() {
        return holdings;
    }

    @Override
    public Point pos() {
        return Point.UNKNOWN;
    }

    @Override
    public CacheValues cacheValues() {
        return cache;
    }

    @Override
    public List<String> groups() {
        return List.of(AccountFacts.DEFAULT_GROUP);
    }

    @Override
    public String username() {
        return "probe";
    }
}
