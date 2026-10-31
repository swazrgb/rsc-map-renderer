package openrsc.nav.eligibility;

import java.util.Arrays;
import java.util.TreeSet;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.nav.eligibility.EligibilityInputs.Fact;

/**
 * The levels at which some gate's answer changes: per skill, the thresholds of the current-level and
 * base-level facts it was built from, and those of the total-level facts. A level's rung is how many
 * of its thresholds it has reached; every level on one rung answers each of those gates alike, so
 * two accounts with equal rungs are indistinguishable to them. A skill no fact names has no
 * thresholds and every level is on rung 0.
 *
 * <p>Immutable, and safe to share between threads.
 */
public final class SkillBreakpoints {

    private final int[][] current;
    private final int[][] base;
    private final int[] currentSkills;
    private final int[] baseSkills;
    private final int[] total;

    private SkillBreakpoints(int[][] current, int[][] base, int[] total) {
        this.current = current;
        this.base = base;
        this.total = total;
        this.currentSkills = gated(current);
        this.baseSkills = gated(base);
    }

    /** The breakpoints of {@code facts}; facts that read no level are ignored. */
    public static SkillBreakpoints of(Iterable<Fact> facts) {
        TreeSet<Integer>[] cur = sets();
        TreeSet<Integer>[] bas = sets();
        TreeSet<Integer> tot = new TreeSet<>();
        for (Fact f : facts) {
            if (f.kind() == Fact.Kind.SKILL_AT_LEAST) {
                cur[f.skill().ordinal()].add(f.threshold());
            } else if (f.kind() == Fact.Kind.BASE_SKILL_AT_LEAST) {
                bas[f.skill().ordinal()].add(f.threshold());
            } else if (f.kind() == Fact.Kind.TOTAL_LEVEL_AT_LEAST) {
                tot.add(f.threshold());
            }
        }
        return new SkillBreakpoints(
                arrays(cur),
                arrays(bas),
                tot.stream().mapToInt(Integer::intValue).toArray());
    }

    public static SkillBreakpoints of(Fact[] facts) {
        return of(Arrays.asList(facts));
    }

    /** The distinct current-level thresholds of {@code skill}, ascending; empty when none. */
    public int[] current(Skill skill) {
        return current[skill.ordinal()].clone();
    }

    /** The distinct base-level thresholds of {@code skill}, ascending; empty when none. */
    public int[] base(Skill skill) {
        return base[skill.ordinal()].clone();
    }

    /** How many of {@code skill}'s current-level thresholds are at or below {@code level}. */
    public int currentRung(Skill skill, int level) {
        return rung(current[skill.ordinal()], level);
    }

    /**
     * Every rung of {@code stats}, written into a new array from index {@code offset} on: the
     * current-level rung of each skill with such thresholds, then the base-level ones, both in skill
     * order, then the total level's when there are total-level thresholds. The first {@code offset}
     * entries are left 0 for the caller.
     */
    public int[] rungs(Stats stats, int offset) {
        int[] out = new int[offset + currentSkills.length + baseSkills.length + (total.length > 0 ? 1 : 0)];
        int k = offset;
        for (int s : currentSkills) {
            out[k++] = rung(current[s], stats.level(s));
        }
        for (int s : baseSkills) {
            out[k++] = rung(base[s], stats.baseLevel(s));
        }
        if (total.length > 0) {
            out[k] = rung(total, stats.totalLevel());
        }
        return out;
    }

    private static int rung(int[] thresholds, int level) {
        int lo = 0;
        int hi = thresholds.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (thresholds[mid] <= level) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @SuppressWarnings("unchecked")
    private static TreeSet<Integer>[] sets() {
        TreeSet<Integer>[] sets = new TreeSet[Skill.COUNT];
        for (int i = 0; i < sets.length; i++) {
            sets[i] = new TreeSet<>();
        }
        return sets;
    }

    private static int[][] arrays(TreeSet<Integer>[] sets) {
        int[][] out = new int[sets.length][];
        for (int i = 0; i < sets.length; i++) {
            out[i] = sets[i].stream().mapToInt(Integer::intValue).toArray();
        }
        return out;
    }

    private static int[] gated(int[][] thresholds) {
        return java.util.stream.IntStream.range(0, thresholds.length)
                .filter(i -> thresholds[i].length > 0)
                .toArray();
    }
}
