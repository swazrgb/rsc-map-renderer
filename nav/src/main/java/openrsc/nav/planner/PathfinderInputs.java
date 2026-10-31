package openrsc.nav.planner;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.SkillBreakpoints;

/**
 * The catalogue of account inputs that can change a plan, derived from the transport gates: each
 * skill level, item count, quest stage and player-cache value some gate tests, with the transports
 * each one opens.
 *
 * <p>Every item a gate reads by id is an {@link ItemInput} of its own, a family member included; an
 * {@link ItemGroup} only names a family a gate accepts any one of. Every {@code transports} count
 * is exact; the example {@link Opening} lists beside them hold at most ten.
 */
public final class PathfinderInputs {

    private static final int MAX_EXAMPLES = 10;

    // The skills the combat level is computed from. No gate tests them, but danger avoidance reads
    // the combat level.
    private static final List<Skill> COMBAT_SKILLS =
            List.of(Skill.ATTACK, Skill.DEFENSE, Skill.STRENGTH, Skill.HITS, Skill.RANGED, Skill.PRAYER, Skill.MAGIC);

    private PathfinderInputs() {}

    /**
     * Skills come in {@link Skill} order and quests by id; items, groups and cache values most-read
     * first. {@code policy} lists the routing switches, and {@code derived} names the read-outs a
     * plan computes from the account rather than takes as input.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            Totals totals,
            List<SkillInput> skills,
            List<String> combatSkills,
            List<ItemGroup> itemGroups,
            List<ItemInput> items,
            List<QuestInput> quests,
            List<CacheInput> cache,
            List<PolicyInput> policy,
            List<String> derived) {}

    /**
     * {@code gated} transports have a condition, {@code never} of them can never be taken;
     * {@code gates} counts the distinct conditions and {@code facts} the distinct reads they make.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Totals(int transports, int gated, int never, int gates, int facts) {}

    /**
     * A transport by name and the tile to show it at: the scenery it acts on, else its boundary,
     * else its first entry tile. Both coordinates are {@code null} when it has none of those.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Opening(String name, Integer x, Integer z) {}

    public static Opening openingOf(Transport t) {
        Point at = t.sceneryAnchor() != null
                ? t.sceneryAnchor()
                : t.boundary() != null
                        ? Point.of(t.boundary().x(), t.boundary().y())
                        : t.entries().isEmpty() ? null : t.entries().get(0);
        return at == null ? new Opening(t.name(), null, null) : new Opening(t.name(), at.x(), at.y());
    }

    /**
     * A value some gate's answer changes at, the number of transports whose gates test it, and
     * examples of them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Threshold(int at, int transports, List<Opening> opens) {}

    /**
     * {@code breakpoints} are the current and base levels this skill's gates test, ascending. A
     * skill with none is listed only because it feeds the combat level.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillInput(String skill, boolean combat, List<Threshold> breakpoints) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemRef(int id, String name) {}

    /** A family some gate accepts any one of, under the gate's wording ("a pickaxe"). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemGroup(String label, List<ItemRef> items, int transports) {}

    /**
     * {@code carry} are the counts gates ask for, {@code mustNotCarry} the most a gate tolerates
     * carrying. {@code worn} when some gate wants the item worn, which it also counts as met by
     * carrying one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemInput(
            int id, String name, int transports, List<Threshold> carry, List<Threshold> mustNotCarry, boolean worn) {}

    /**
     * {@code atLeast} are stages a gate accepts from, completion included; {@code exact} single
     * stages completion does not satisfy; {@code done} the gates that want the quest complete, or
     * {@code null} when none does.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestInput(
            int id, String name, int transports, List<Threshold> atLeast, List<Threshold> exact, Threshold done) {}

    /**
     * A player-cache value gates read. {@code kind} is {@code bool} or {@code int}; a bool's
     * {@code targets} are 0 and 1, an int's the minimums and exact values asked for.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CacheInput(String key, String kind, int transports, List<Threshold> targets) {}

    /** A {@link NavPolicy} switch, keyed by the request field that sets it, and its default value. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicyInput(String key, String label, boolean defaultValue) {}

    /** The catalogue for {@code pf}'s transports. */
    public static Response of(Pathfinder pf) {
        ItemDefs itemDefs = pf.serverData() == null ? null : pf.serverData().itemDefs();
        Uses uses = new Uses();
        Set<Prereq> gates = new LinkedHashSet<>();
        int gated = 0;
        int never = 0;
        for (Transport t : pf.transports()) {
            Prereq gate = t.prereq();
            if (gate == Prereq.ALWAYS) {
                continue;
            }
            gated++;
            if (gate == Prereq.NEVER) {
                never++;
            }
            gates.add(gate);
            for (Fact f : gate.facts()) {
                uses.add(f, t);
            }
        }

        List<ItemGroup> groups = new ArrayList<>();
        for (Pathfinder.ItemGroup g : pf.itemGroups()) {
            List<ItemRef> members = new ArrayList<>(g.itemIds().length);
            for (int id : g.itemIds()) {
                members.add(new ItemRef(id, nameOf(itemDefs, id)));
            }
            groups.add(new ItemGroup(g.label(), members, g.transports()));
        }

        return new Response(
                new Totals(pf.transports().size(), gated, never, gates.size(), uses.facts.size()),
                skills(pf.skillBreakpoints(), uses),
                COMBAT_SKILLS.stream().map(Skill::name).toList(),
                groups,
                items(uses, itemDefs),
                quests(uses),
                cache(uses),
                List.of(
                        new PolicyInput(
                                "danger", "Avoid danger", NavPolicy.DEFAULT.danger() == NavPolicy.DangerAvoidance.ON),
                        new PolicyInput(
                                "berth",
                                "Avoid aggressive NPCs",
                                NavPolicy.DEFAULT.berth().berthByDefault()),
                        new PolicyInput("teleportSpells", "Teleportation spells", NavPolicy.DEFAULT.teleportSpells()),
                        new PolicyInput(
                                "teleportJewellery", "Teleportation jewellery", NavPolicy.DEFAULT.teleportJewellery())),
                List.of("combatLevel", "questPoints", "wildernessLevel"));
    }

    private static List<SkillInput> skills(SkillBreakpoints breakpoints, Uses uses) {
        List<SkillInput> out = new ArrayList<>();
        for (Skill sk : Skill.values()) {
            List<Threshold> rungs = new ArrayList<>();
            for (int at : breakpoints.current(sk)) {
                rungs.add(uses.threshold(
                        at, f -> f.kind() == Fact.Kind.SKILL_AT_LEAST && f.skill() == sk && f.threshold() == at));
            }
            for (int at : breakpoints.base(sk)) {
                rungs.add(uses.threshold(
                        at, f -> f.kind() == Fact.Kind.BASE_SKILL_AT_LEAST && f.skill() == sk && f.threshold() == at));
            }
            boolean combat = COMBAT_SKILLS.contains(sk);
            if (!rungs.isEmpty() || combat) {
                rungs.sort(Comparator.comparingInt(Threshold::at));
                out.add(new SkillInput(sk.name(), combat, rungs));
            }
        }
        return List.copyOf(out);
    }

    private static List<ItemInput> items(Uses uses, ItemDefs itemDefs) {
        Map<Integer, List<Fact>> byItem = new LinkedHashMap<>();
        for (Fact f : uses.facts) {
            switch (f.kind()) {
                case CARRIED_AT_LEAST, WEARING, NOT_CARRYING ->
                    byItem.computeIfAbsent(f.itemId(), k -> new ArrayList<>()).add(f);
                default -> {}
            }
        }
        List<ItemInput> out = new ArrayList<>(byItem.size());
        byItem.forEach((id, facts) -> {
            List<Threshold> carry = new ArrayList<>();
            List<Threshold> mustNot = new ArrayList<>();
            boolean worn = false;
            int transports = 0;
            for (Fact f : facts) {
                transports += uses.count(f);
                switch (f.kind()) {
                    case CARRIED_AT_LEAST -> carry.add(uses.threshold(f.threshold(), f::equals));
                    case NOT_CARRYING -> mustNot.add(uses.threshold(f.threshold(), f::equals));
                    case WEARING -> {
                        worn = true;
                        carry.add(uses.threshold(1, f::equals));
                    }
                    default -> {}
                }
            }
            carry.sort(Comparator.comparingInt(Threshold::at));
            mustNot.sort(Comparator.comparingInt(Threshold::at));
            out.add(new ItemInput(id, nameOf(itemDefs, id), transports, carry, mustNot, worn));
        });
        out.sort(Comparator.comparingInt(ItemInput::transports).reversed());
        return out;
    }

    private static List<QuestInput> quests(Uses uses) {
        Map<Integer, List<Fact>> byQuest = new LinkedHashMap<>();
        for (Fact f : uses.facts) {
            switch (f.kind()) {
                case QUEST_AT_LEAST, QUEST_EQUALS, QUEST_COMPLETE ->
                    byQuest.computeIfAbsent(f.itemId(), k -> new ArrayList<>()).add(f);
                default -> {}
            }
        }
        List<QuestInput> out = new ArrayList<>(byQuest.size());
        byQuest.forEach((id, facts) -> {
            List<Threshold> atLeast = new ArrayList<>();
            List<Threshold> exact = new ArrayList<>();
            Threshold done = null;
            int transports = 0;
            for (Fact f : facts) {
                transports += uses.count(f);
                switch (f.kind()) {
                    case QUEST_AT_LEAST -> atLeast.add(uses.threshold(f.threshold(), f::equals));
                    case QUEST_EQUALS -> exact.add(uses.threshold(f.threshold(), f::equals));
                    case QUEST_COMPLETE -> done = uses.threshold(-1, f::equals);
                    default -> {}
                }
            }
            atLeast.sort(Comparator.comparingInt(Threshold::at));
            exact.sort(Comparator.comparingInt(Threshold::at));
            out.add(new QuestInput(id, QuestId.nameOf(id), transports, atLeast, exact, done));
        });
        out.sort(Comparator.comparingInt(QuestInput::id));
        return out;
    }

    private static List<CacheInput> cache(Uses uses) {
        Map<String, List<Fact>> byKey = new LinkedHashMap<>();
        for (Fact f : uses.facts) {
            if (f.kind() == Fact.Kind.CACHE_BOOL
                    || f.kind() == Fact.Kind.CACHE_INT_AT_LEAST
                    || f.kind() == Fact.Kind.CACHE_INT_EQUALS) {
                byKey.computeIfAbsent(f.cacheKey(), k -> new ArrayList<>()).add(f);
            }
        }
        List<CacheInput> out = new ArrayList<>(byKey.size());
        byKey.forEach((key, facts) -> {
            List<Threshold> targets = new ArrayList<>();
            int transports = 0;
            String kind = "bool";
            for (Fact f : facts) {
                transports += uses.count(f);
                if (f.kind() != Fact.Kind.CACHE_BOOL) {
                    kind = "int";
                }
                targets.add(uses.threshold(f.threshold(), f::equals));
            }
            targets.sort(Comparator.comparingInt(Threshold::at));
            out.add(new CacheInput(key, kind, transports, targets));
        });
        out.sort(Comparator.comparingInt(CacheInput::transports).reversed());
        return out;
    }

    // ItemDefs.nameOf answers "?" for an id it does not hold.
    private static String nameOf(ItemDefs defs, int id) {
        if (defs != null && id < defs.size()) {
            String known = defs.nameOf(id);
            if (known != null && !known.equals("?")) {
                return known;
            }
        }
        return ItemId.nameOf(id);
    }

    private static final class Uses {
        final Set<Fact> facts = new LinkedHashSet<>();
        private final Map<Fact, List<Opening>> examples = new LinkedHashMap<>();
        private final Map<Fact, Integer> counts = new LinkedHashMap<>();

        void add(Fact f, Transport transport) {
            facts.add(f);
            counts.merge(f, 1, Integer::sum);
            List<Opening> names = examples.computeIfAbsent(f, k -> new ArrayList<>(MAX_EXAMPLES));
            if (names.size() < MAX_EXAMPLES) {
                names.add(openingOf(transport));
            }
        }

        int count(Fact f) {
            return counts.getOrDefault(f, 0);
        }

        Threshold threshold(int at, java.util.function.Predicate<Fact> matches) {
            int transports = 0;
            List<Opening> opens = new ArrayList<>(MAX_EXAMPLES);
            for (Fact f : facts) {
                if (!matches.test(f)) {
                    continue;
                }
                transports += count(f);
                for (Opening name : examples.getOrDefault(f, List.of())) {
                    if (opens.size() < MAX_EXAMPLES) {
                        opens.add(name);
                    }
                }
            }
            return new Threshold(at, transports, List.copyOf(opens));
        }
    }
}
