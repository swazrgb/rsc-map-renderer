package openrsc.bot.api.eligibility;

import java.util.List;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Holdings;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;

/**
 * The account state that routing and eligibility checks read.
 *
 * <p>An implementation must be an immutable picture of one moment: every read of one instance
 * describes the same account at the same time, so it may be read from any thread while the account
 * itself moves on.
 */
public interface AccountFacts {

    /**
     * An account as plain data, not logged in. Position, holdings and server data are required;
     * absent cache values read as {@link CacheValues#EMPTY} and absent groups as
     * {@link #DEFAULT_GROUP}; a null {@code pos}, {@code owned} or {@code serverData} throws
     * {@link IllegalArgumentException}.
     */
    record Snapshot(
            ServerData serverData,
            Point pos,
            Holdings owned,
            Stats stats,
            Quests quests,
            CacheValues cacheValues,
            String username,
            List<String> groups)
            implements AccountFacts {

        public Snapshot {
            if (pos == null) {
                throw new IllegalArgumentException("a plannable account has a position — this is a pathfinding input");
            }
            if (owned == null) {
                throw new IllegalArgumentException("a plannable account has holdings");
            }
            if (serverData == null) {
                throw new IllegalArgumentException("an account belongs to a server — pass its defs, never a stand-in");
            }
            cacheValues = cacheValues == null ? CacheValues.EMPTY : cacheValues;
            groups = groups == null || groups.isEmpty() ? List.of(DEFAULT_GROUP) : List.copyOf(groups);
        }

        /** A copy of what {@code facts} reads, now not logged in. */
        public static Snapshot from(AccountFacts facts) {
            Holdings held = facts.owned();
            return new Snapshot(
                    facts.serverData(),
                    facts.pos(),
                    held,
                    facts.stats(),
                    facts.quests(),
                    facts.cacheValues(),
                    facts.username(),
                    facts.groups());
        }

        public Snapshot(
                ServerData serverData,
                Inventory inventory,
                Bank bank,
                Stats stats,
                Quests quests,
                Point pos,
                CacheValues cacheValues,
                String username,
                List<String> groups) {
            this(serverData, pos, new Holdings(inventory, bank), stats, quests, cacheValues, username, groups);
        }

        public Snapshot(
                ServerData serverData,
                Inventory inventory,
                Bank bank,
                Stats stats,
                Quests quests,
                Point pos,
                CacheValues cacheValues) {
            this(serverData, inventory, bank, stats, quests, pos, cacheValues, null, List.of(DEFAULT_GROUP));
        }

        public Snapshot(ServerData serverData, Inventory inventory, Bank bank, Stats stats, Quests quests, Point pos) {
            this(serverData, inventory, bank, stats, quests, pos, CacheValues.EMPTY);
        }

        @Override
        public boolean online() {
            return false;
        }

        @Override
        public Inventory inventory() {
            return owned.inventory();
        }

        @Override
        public Bank bank() {
            return owned.bank();
        }
    }

    /** The group of an account tagged with none. */
    String DEFAULT_GROUP = "default";

    /** The group tag of an account that fights only in the defensive style. */
    String DEFENSIVE_ONLY_GROUP = "thieving";

    /** The prefix of a magic pure's group tag; such an account also fights only defensively. */
    String MAGIC_PURE_GROUP_PREFIX = "magicpure-";

    ServerData serverData();

    /** Where the account stands; never null, and {@link Point#UNKNOWN} when that is not known. */
    Point pos();

    Inventory inventory();

    Bank bank();

    /** {@link #inventory()} and {@link #bank()} as one pair. */
    Holdings owned();

    Stats stats();

    Quests quests();

    String username();

    /** The account's group tags; {@code [}{@link #DEFAULT_GROUP}{@code ]} when it has none. */
    List<String> groups();

    /** Whether the account is logged in; an instance kept past a logout still answers as it did. */
    boolean online();

    /** The player-cache values published for this account; never null. */
    CacheValues cacheValues();

    /**
     * Whether an account in {@code groups} fights only in the defensive style: one tagged
     * {@link #DEFENSIVE_ONLY_GROUP} or with a {@link #MAGIC_PURE_GROUP_PREFIX} tag.
     */
    static boolean defensiveOnly(List<String> groups) {
        if (groups == null) {
            return false;
        }
        for (String g : groups) {
            if (DEFENSIVE_ONLY_GROUP.equals(g) || (g != null && g.startsWith(MAGIC_PURE_GROUP_PREFIX))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an account in {@code groups} must gain no Prayer experience, which would raise its
     * combat level. The same accounts as {@link #defensiveOnly}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L424">Formulae.getCombatLevel</a>
     */
    static boolean avoidsPrayerXp(List<String> groups) {
        return defensiveOnly(groups);
    }

    /** This account carrying {@code inventory} instead; {@link #owned()} follows it. */
    default AccountFacts withInventory(Inventory inventory) {
        return new AccountView(this, inventory, null, null);
    }

    /** This account standing on tile {@code (x, z)} instead. */
    default AccountFacts at(int x, int z) {
        return new AccountView(this, null, Point.of(x, z), null);
    }

    default AccountFacts withQuests(Quests quests) {
        return new AccountView(this, null, null, quests);
    }

    default AccountFacts withStats(Stats stats) {
        return new AccountView(this, null, null, null, stats);
    }

    /** The combat level of {@code stats}' base levels. */
    static int combatLevel(Stats stats) {
        return combatLevel(
                stats.baseLevel(Skill.ATTACK),
                stats.baseLevel(Skill.DEFENSE),
                stats.baseLevel(Skill.STRENGTH),
                stats.baseLevel(Skill.HITS),
                stats.baseLevel(Skill.RANGED),
                stats.baseLevel(Skill.PRAYER),
                stats.baseLevel(Skill.MAGIC));
    }

    /**
     * The server's combat level formula, with Ranged, Prayer and Magic all counting, as they do by
     * default. 0 when Attack, Defense, Strength and Hits are all 0, as for stats not yet known.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L424">Formulae.getCombatLevel</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L486">ServerConfiguration.initConfig</a>
     */
    static int combatLevel(int att, int def, int str, int hits, int range, int pray, int magic) {
        if (att + def + str + hits == 0) {
            return 0;
        }
        double attack = att + str;
        double defense = def + hits;
        double mage = (pray + magic) / 8.0;
        double level =
                (attack < range * 1.5) ? (2 * defense + 3 * range) / 8.0 + mage : (attack + defense) / 4.0 + mage;
        return (int) Math.floor(level);
    }

    /**
     * The wilderness level of tile {@code (x, z)} on any floor, as the server computes it; 0 outside
     * the wilderness.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L372">Point.wildernessLevel</a>
     */
    static int wildernessLevel(int x, int z) {
        if (x >= 336) {
            return 0;
        }
        int wild = 2203 - (z + (1776 - 944 * (z / 944)));
        return wild > 0 ? 1 + wild / 6 : 0;
    }
}
