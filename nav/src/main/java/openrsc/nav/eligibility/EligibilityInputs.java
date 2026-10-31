package openrsc.nav.eligibility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.quest.QuestPoints;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.nav.NavPolicy;

/**
 * The account state a {@link Prereq} reads, as a set of {@link Fact}s: threshold comparisons whose
 * truth values are all the condition may depend on. Two accounts on which every declared fact agrees
 * get the same answer from it, so a result can be shared between them. Declaring a fact the condition
 * does not read is harmless; leaving one out lets two accounts that differ in it share a wrong answer.
 *
 * <p>Instances are immutable.
 */
public final class EligibilityInputs {

    /**
     * One threshold comparison on an account. What {@code itemId}, {@code skill}, {@code threshold}
     * and {@code cacheKey} hold depends on the {@link Kind}; a field the kind does not use is
     * {@code -1}, {@code 0} or {@code null}. Quest facts keep the server's quest id in
     * {@code itemId}. Item counts are of unnoted items, worn ones included.
     */
    public record Fact(Kind kind, int itemId, Skill skill, int threshold, String cacheKey) {

        public enum Kind {
            /** At least {@code threshold} of {@code itemId} carried. */
            CARRIED_AT_LEAST,
            /** {@code itemId} carried, worn or not: a carried item can be put on before the gate. */
            WEARING,
            /**
             * The current level of {@code skill} at least {@code threshold}. For Hits the base level
             * also passes while a {@link Meals} meal is carried, since it can be eaten back first.
             */
            SKILL_AT_LEAST,
            /** The base level of {@code skill}, which no drain lowers, at least {@code threshold}. */
            BASE_SKILL_AT_LEAST,
            /**
             * Quest {@code itemId} at stage {@code threshold} or later, or complete (stage -1).
             *
             * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Quests.java#L58">Quests.QUEST_STAGE_COMPLETED</a>
             */
            QUEST_AT_LEAST,
            /** Quest {@code itemId} complete. */
            QUEST_COMPLETE,
            /** Quest {@code itemId} at exactly stage {@code threshold}; completion does not count. */
            QUEST_EQUALS,
            /** Any item {@link MeleeWeapons} counts carried. */
            CARRYING_MELEE_WEAPON,
            /** At most {@code threshold} of {@code itemId} carried; 0 means none. */
            NOT_CARRYING,
            /** Room in the pack for {@code threshold} more of {@code itemId}. */
            ROOM_FOR,
            /** Nothing carried that {@link EntranaAccess} blocks. */
            NOT_CARRYING_COMBAT_GEAR,
            /** Nothing carried that {@link GlarialTombAccess} refuses. */
            NOT_CARRYING_TOMB_BANNED,
            /** Nothing carried that {@link MageArenaAccess} refuses. */
            NOT_CARRYING_MAGE_ARENA_BANNED,
            /**
             * The server player-cache boolean {@code cacheKey} on ({@code threshold} 1) or off (0).
             * The account answers from the values it tracks; a key it does not track passes.
             */
            CACHE_BOOL,
            /**
             * The server player-cache int {@code cacheKey} at least {@code threshold}. A key the
             * account does not track passes.
             */
            CACHE_INT_AT_LEAST,
            /**
             * The server player-cache int {@code cacheKey} exactly {@code threshold}. A key the account
             * does not track passes.
             */
            CACHE_INT_EQUALS,
            /** Quest points, summed over the account's completed quests, at least {@code threshold}. */
            QUEST_POINTS_AT_LEAST,
            /**
             * The sum of every base level at least {@code threshold}.
             *
             * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Skills.java#L88">Skills.getTotalLevel</a>
             */
            TOTAL_LEVEL_AT_LEAST,
            /** {@link NavPolicy#teleportSpells()} set: the caller's policy, not account state. */
            TELEPORT_SPELLS_ALLOWED,
            /** {@link NavPolicy#teleportJewellery()} set: the caller's policy, not account state. */
            TELEPORT_JEWELLERY_ALLOWED,
        }

        public static Fact carriedAtLeast(int itemId, int count) {
            return new Fact(Kind.CARRIED_AT_LEAST, itemId, null, count, null);
        }

        public static Fact notCarrying(int itemId) {
            return new Fact(Kind.NOT_CARRYING, itemId, null, 0, null);
        }

        public static Fact notCarrying(int itemId, int atMost) {
            return new Fact(Kind.NOT_CARRYING, itemId, null, atMost, null);
        }

        public static Fact roomFor(int itemId, int count) {
            return new Fact(Kind.ROOM_FOR, itemId, null, count, null);
        }

        public static Fact wearing(int itemId) {
            return new Fact(Kind.WEARING, itemId, null, 1, null);
        }

        public static Fact skillAtLeast(Skill skill, int level) {
            return new Fact(Kind.SKILL_AT_LEAST, -1, skill, level, null);
        }

        public static Fact baseSkillAtLeast(Skill skill, int level) {
            return new Fact(Kind.BASE_SKILL_AT_LEAST, -1, skill, level, null);
        }

        public static Fact questAtLeast(int questId, int stage) {
            return new Fact(Kind.QUEST_AT_LEAST, questId, null, stage, null);
        }

        public static Fact questComplete(int questId) {
            return new Fact(Kind.QUEST_COMPLETE, questId, null, -1, null);
        }

        public static Fact questEquals(int questId, int stage) {
            return new Fact(Kind.QUEST_EQUALS, questId, null, stage, null);
        }

        public static Fact carryingMeleeWeapon() {
            return new Fact(Kind.CARRYING_MELEE_WEAPON, -1, null, 0, null);
        }

        public static Fact notCarryingCombatGear() {
            return new Fact(Kind.NOT_CARRYING_COMBAT_GEAR, -1, null, 0, null);
        }

        public static Fact notCarryingTombBanned() {
            return new Fact(Kind.NOT_CARRYING_TOMB_BANNED, -1, null, 0, null);
        }

        public static Fact notCarryingMageArenaBanned() {
            return new Fact(Kind.NOT_CARRYING_MAGE_ARENA_BANNED, -1, null, 0, null);
        }

        public static Fact cacheBool(String key, boolean target) {
            return new Fact(Kind.CACHE_BOOL, -1, null, target ? 1 : 0, key);
        }

        private static final Set<String> CACHE_READ_BY_PRESENCE = Set.of("stalagmite", "shantay_jail");

        /**
         * Whether the server tests cache boolean {@code key} by its presence alone, so that a stored
         * {@code false} reads as on and off means the key is absent. Every other key is read by value.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassOrbs.java#L119">UndergroundPassOrbs.onOpLoc</a>
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L235">ShantayPassNpcs.action</a>
         */
        public static boolean cacheReadByPresence(String key) {
            return CACHE_READ_BY_PRESENCE.contains(key);
        }

        public static Fact cacheIntAtLeast(String key, int min) {
            return new Fact(Kind.CACHE_INT_AT_LEAST, -1, null, min, key);
        }

        public static Fact cacheIntEquals(String key, int value) {
            return new Fact(Kind.CACHE_INT_EQUALS, -1, null, value, key);
        }

        public static Fact questPointsAtLeast(int min) {
            return new Fact(Kind.QUEST_POINTS_AT_LEAST, -1, null, min, null);
        }

        public static Fact totalLevelAtLeast(int min) {
            return new Fact(Kind.TOTAL_LEVEL_AT_LEAST, -1, null, min, null);
        }

        public static Fact teleportSpellsAllowed() {
            return new Fact(Kind.TELEPORT_SPELLS_ALLOWED, -1, null, 1, null);
        }

        public static Fact teleportJewelleryAllowed() {
            return new Fact(Kind.TELEPORT_JEWELLERY_ALLOWED, -1, null, 1, null);
        }

        /** The fact in words, naming items by their {@link ItemId} constant and quests by name. */
        public String describe() {
            return display(null);
        }

        /** {@link #describe()} with items named as {@code defs}' world names them; as it does when null. */
        public String display(@Nullable ItemDefs defs) {
            return switch (kind) {
                case CARRIED_AT_LEAST -> "carrying \u2265" + threshold + " " + itemName(defs, itemId);
                case NOT_CARRYING -> "carrying \u2264" + threshold + " " + itemName(defs, itemId);
                case ROOM_FOR -> "room for " + threshold + " " + itemName(defs, itemId);
                case WEARING -> "wearing " + itemName(defs, itemId);
                case SKILL_AT_LEAST -> skill.name().toLowerCase(Locale.ROOT) + " \u2265 " + threshold;
                case BASE_SKILL_AT_LEAST -> "base " + skill.name().toLowerCase(Locale.ROOT) + " \u2265 " + threshold;
                case QUEST_AT_LEAST -> QuestId.nameOf(itemId) + " at stage \u2265 " + threshold + " or done";
                case QUEST_COMPLETE -> QuestId.nameOf(itemId) + " complete";
                case QUEST_EQUALS -> QuestId.nameOf(itemId) + " at stage " + threshold + " exactly";
                case CARRYING_MELEE_WEAPON -> "carrying a melee weapon";
                case NOT_CARRYING_COMBAT_GEAR -> "no Entrana-blocked gear";
                case NOT_CARRYING_TOMB_BANNED -> "no tomb-banned gear";
                case NOT_CARRYING_MAGE_ARENA_BANNED -> "no gear the Mage Arena barrier refuses";
                case CACHE_BOOL -> "cache " + cacheKey + "=" + (cacheTarget() ? "on" : "off");
                case CACHE_INT_AT_LEAST -> "cache " + cacheKey + " \u2265 " + threshold;
                case CACHE_INT_EQUALS -> "cache " + cacheKey + " = " + threshold;
                case QUEST_POINTS_AT_LEAST -> "quest points \u2265 " + threshold;
                case TOTAL_LEVEL_AT_LEAST -> "total level \u2265 " + threshold;
                case TELEPORT_SPELLS_ALLOWED -> "teleport spells allowed";
                case TELEPORT_JEWELLERY_ALLOWED -> "teleport jewellery allowed";
            };
        }

        private static String itemName(@Nullable ItemDefs defs, int itemId) {
            return defs != null ? defs.nameOf(itemId) : ItemId.nameOf(itemId);
        }

        /** For a {@link Kind#CACHE_BOOL} fact, the value the key must hold. */
        public boolean cacheTarget() {
            return threshold != 0;
        }

        /** Whether the fact holds for the account in {@code ctx}. */
        public boolean test(RouteContext ctx) {
            AccountFacts state = ctx.account();
            NavPolicy policy = ctx.policy();
            return switch (kind) {
                case CARRIED_AT_LEAST -> state.inventory().count(itemId) >= threshold;
                case NOT_CARRYING -> state.inventory().count(itemId) <= threshold;
                case ROOM_FOR -> state.inventory().hasSpaceFor(ctx.serverData(), itemId, threshold);
                case WEARING -> {
                    yield state.inventory().count(itemId) >= 1;
                }
                case SKILL_AT_LEAST ->
                    state.stats().level(skill) >= threshold
                            || (foodLifts()
                                    && state.stats().baseLevel(skill) >= threshold
                                    && Meals.carried(state.inventory()));
                case BASE_SKILL_AT_LEAST -> state.stats().baseLevel(skill) >= threshold;
                case QUEST_AT_LEAST -> {
                    int stage = state.quests().stage(itemId);
                    yield stage < 0 || stage >= threshold;
                }
                case QUEST_COMPLETE -> state.quests().stage(itemId) < 0;
                case QUEST_EQUALS -> state.quests().stage(itemId) == threshold;
                case CARRYING_MELEE_WEAPON -> MeleeWeapons.carried(state.inventory(), ctx.serverData());
                case NOT_CARRYING_COMBAT_GEAR -> !EntranaAccess.carriesBlockedGear(state, ctx.serverData());
                case NOT_CARRYING_TOMB_BANNED -> !GlarialTombAccess.carriesBannedGear(state, ctx.serverData());
                case NOT_CARRYING_MAGE_ARENA_BANNED -> !MageArenaAccess.carriesRefusedGear(state, ctx.serverData());
                case CACHE_BOOL -> state.cacheValues().bool(cacheKey, cacheTarget()) == cacheTarget();
                case CACHE_INT_AT_LEAST -> state.cacheValues().intValue(cacheKey, threshold) >= threshold;
                case CACHE_INT_EQUALS -> state.cacheValues().intValue(cacheKey, threshold) == threshold;
                case QUEST_POINTS_AT_LEAST -> state.quests().questPoints() >= threshold;
                case TOTAL_LEVEL_AT_LEAST -> state.stats().totalLevel() >= threshold;
                case TELEPORT_SPELLS_ALLOWED -> policy.teleportSpells();
                case TELEPORT_JEWELLERY_ALLOWED -> policy.teleportJewellery();
            };
        }

        /** Whether {@link #testBanked} can answer differently from {@link #test} for this fact. */
        public boolean bankRelaxed() {
            return kind == Kind.CARRIED_AT_LEAST
                    || kind == Kind.ROOM_FOR
                    || kind == Kind.WEARING
                    || kind == Kind.CARRYING_MELEE_WEAPON
                    || bansItems()
                    || foodLifts();
        }

        /** Whether this fact refuses carried items, which a stop can bank. */
        public boolean bansItems() {
            return kind == Kind.NOT_CARRYING
                    || kind == Kind.NOT_CARRYING_COMBAT_GEAR
                    || kind == Kind.NOT_CARRYING_TOMB_BANNED
                    || kind == Kind.NOT_CARRYING_MAGE_ARENA_BANNED;
        }

        /**
         * For a fact that refuses carried items, each one {@code pack} holds more of than it allows,
         * with the most it allows, counted as the item: {@link Kind#NOT_CARRYING}'s threshold, what the
         * tomb lets in worn off the pack, else 0. Empty where the fact holds or refuses nothing. Banking
         * down to those counts, and the notes too where {@link #refusesNotes}, makes it hold.
         */
        public Map<Integer, Long> refused(Inventory pack, ServerData sd) {
            Map<Integer, Long> out = new LinkedHashMap<>();
            switch (kind) {
                case NOT_CARRYING -> {
                    if (pack.count(itemId) > threshold) {
                        out.put(itemId, (long) threshold);
                    }
                }
                case NOT_CARRYING_COMBAT_GEAR, NOT_CARRYING_TOMB_BANNED, NOT_CARRYING_MAGE_ARENA_BANNED -> {
                    for (InventorySlot it : pack.slots()) {
                        boolean refuses =
                                switch (kind) {
                                    case NOT_CARRYING_COMBAT_GEAR -> EntranaAccess.blocked(it.id(), sd);
                                    case NOT_CARRYING_TOMB_BANNED ->
                                        pack.takesPackSlot(it) && GlarialTombAccess.isBanned(sd, it.id());
                                    default -> MageArenaAccess.refused(it.id(), sd);
                                };
                        if (refuses) {
                            out.put(it.id(), 0L);
                        }
                    }
                    if (kind == Kind.NOT_CARRYING_TOMB_BANNED) {
                        for (InventorySlot it : pack.slots()) {
                            if (!pack.takesPackSlot(it) && out.containsKey(it.id())) {
                                out.merge(it.id(), (long) it.amount(), Long::sum);
                            }
                        }
                    }
                }
                default -> {}
            }
            return out;
        }

        /** Whether this fact refuses an item's notes as well as the item: the gear checks read every slot. */
        public boolean refusesNotes() {
            return bansItems() && kind != Kind.NOT_CARRYING;
        }

        /** Whether this is a current-level Hits gate, which carried food lets the base level pass. */
        public boolean foodLifts() {
            return kind == Kind.SKILL_AT_LEAST && skill == Skill.HITS;
        }

        /**
         * Whether {@link #test} reads the carried items. Two views of one account that differ only in
         * what they carry can answer differently only for these facts.
         */
        public boolean readsPack() {
            return switch (kind) {
                case CARRIED_AT_LEAST,
                        NOT_CARRYING,
                        ROOM_FOR,
                        WEARING,
                        CARRYING_MELEE_WEAPON,
                        NOT_CARRYING_COMBAT_GEAR,
                        NOT_CARRYING_TOMB_BANNED,
                        NOT_CARRYING_MAGE_ARENA_BANNED -> true;
                case SKILL_AT_LEAST -> foodLifts();
                case BASE_SKILL_AT_LEAST,
                        QUEST_AT_LEAST,
                        QUEST_COMPLETE,
                        QUEST_EQUALS,
                        CACHE_BOOL,
                        CACHE_INT_AT_LEAST,
                        CACHE_INT_EQUALS,
                        QUEST_POINTS_AT_LEAST,
                        TOTAL_LEVEL_AT_LEAST,
                        TELEPORT_SPELLS_ALLOWED,
                        TELEPORT_JEWELLERY_ALLOWED -> false;
            };
        }

        /**
         * Whether the fact could hold after a bank stop: the carry facts and the Hits gate with the bank's
         * items carried, and {@link Kind#ROOM_FOR} and the facts that refuse an item always, since a stop
         * can bank anything, worn or not, room in the bank allowing. Other facts read as {@link #test}.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L533">Bank.depositItemFromInventory</a>
         */
        public boolean testBanked(RouteContext ctx) {
            if (!bankRelaxed()) {
                return test(ctx);
            }
            AccountFacts state = ctx.account();
            if (kind == Kind.CARRIED_AT_LEAST) {
                return state.inventory().count(NoteForm.BOTH, itemId)
                                + state.bank().count(itemId)
                        >= threshold;
            }
            if (kind == Kind.ROOM_FOR || bansItems()) {
                return true;
            }
            if (kind == Kind.WEARING) {
                return state.inventory().count(NoteForm.BOTH, itemId)
                                + state.bank().count(itemId)
                        >= 1;
            }
            if (kind == Kind.CARRYING_MELEE_WEAPON) {
                return MeleeWeapons.carried(state.inventory(), ctx.serverData())
                        || MeleeWeapons.firstBanked(state.bank(), ctx.serverData()) >= 0;
            }
            if (foodLifts()) {
                return test(ctx)
                        || (state.stats().baseLevel(skill) >= threshold
                                && Meals.bestBanked(state.bank(), ctx.serverData()) >= 0);
            }
            return test(ctx);
        }
    }

    /** A condition that reads no account state. */
    public static final EligibilityInputs NONE = new EligibilityInputs(new Fact[0], new Fact[0]);

    private final Fact[] facts;
    private final Fact[] negatedFacts;

    private EligibilityInputs(Fact[] facts, Fact[] negatedFacts) {
        this.facts = facts;
        this.negatedFacts = negatedFacts;
    }

    /**
     * The given facts without duplicates, in order, and which of them the condition needs to be false;
     * {@link #NONE} for none. {@code negated} should be a subset of {@code facts}.
     */
    public static EligibilityInputs of(Fact[] facts, Fact[] negated) {
        if (facts == null || facts.length == 0) {
            return NONE;
        }
        return new EligibilityInputs(
                new LinkedHashSet<>(Arrays.asList(facts)).toArray(new Fact[0]),
                negated == null ? new Fact[0] : new LinkedHashSet<>(Arrays.asList(negated)).toArray(new Fact[0]));
    }

    public Fact[] facts() {
        return facts.clone();
    }

    /** Whether any declared fact is of {@code kind}, without the copy {@link #facts()} makes. */
    public boolean anyFactOfKind(Fact.Kind kind) {
        for (Fact f : facts) {
            if (f.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /** The declared facts the condition needs to be false; empty for an all-positive one. */
    public Fact[] negatedFacts() {
        return negatedFacts.clone();
    }

    /** The distinct item ids the carry and wear facts name, ascending. */
    public int[] itemIds() {
        return Arrays.stream(facts)
                .filter(f -> f.kind() == Fact.Kind.CARRIED_AT_LEAST || f.kind() == Fact.Kind.WEARING)
                .mapToInt(Fact::itemId)
                .distinct()
                .sorted()
                .toArray();
    }

    /**
     * Quest ids whose point rewards add up to at least {@code min}, picked from the highest reward
     * down so they are few; every rewarding quest when together they fall short of {@code min}.
     */
    public static int[] questIdsForPoints(int min) {
        Integer[] order = new Integer[QuestPoints.REWARDS.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> QuestPoints.REWARDS[b] - QuestPoints.REWARDS[a]);
        List<Integer> picked = new ArrayList<>();
        int sum = 0;
        for (int q : order) {
            if (sum >= min) {
                break;
            }
            if (QuestPoints.REWARDS[q] > 0) {
                picked.add(q);
                sum += QuestPoints.REWARDS[q];
            }
        }
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = picked.get(i);
        }
        return out;
    }
}
