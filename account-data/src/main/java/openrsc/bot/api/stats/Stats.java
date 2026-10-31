package openrsc.bot.api.stats;

import openrsc.bot.api.eligibility.AccountFacts;

/**
 * An account's skills, frozen. Each skill has a current level, which drains and boosts; a base
 * level, which its experience earns; and the experience itself.
 */
public final class Stats {

    public static final Stats EMPTY = new MutableStats().snapshot();

    private final int[] levels;
    private final int[] baseLevels;
    private final int[] xp;

    private final int serverSkillCount;
    private final int combatLevel;

    Stats(int[] levels, int[] baseLevels, int[] xp, int serverSkillCount) {
        this.serverSkillCount = serverSkillCount;
        this.levels = levels.clone();
        this.baseLevels = baseLevels.clone();
        this.xp = xp.clone();
        this.combatLevel = AccountFacts.combatLevel(this);
    }

    public int serverSkillCount() {
        return serverSkillCount;
    }

    /** The current level of skill {@code skillId}; 0 when out of range, as for the raw-id reads below. */
    public int level(int skillId) {
        return skillId >= 0 && skillId < levels.length ? levels[skillId] : 0;
    }

    public int baseLevel(int skillId) {
        return skillId >= 0 && skillId < baseLevels.length ? baseLevels[skillId] : 0;
    }

    public int xp(int skillId) {
        return skillId >= 0 && skillId < xp.length ? xp[skillId] : 0;
    }

    public int level(Skill skill) {
        return levels[skill.id];
    }

    public int baseLevel(Skill skill) {
        return baseLevels[skill.id];
    }

    public int xp(Skill skill) {
        return xp[skill.id];
    }

    /**
     * The base levels summed over the server's skills, as the server sums them.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Skills.java#L88">Skills.getTotalLevel</a>
     */
    public int totalLevel() {
        int total = 0;
        for (int i = 0; i < Math.min(serverSkillCount, baseLevels.length); i++) {
            total += baseLevels[i];
        }
        return total;
    }

    /** A copy with {@code skill}'s current level set to {@code level}. */
    public Stats withLevel(Skill skill, int level) {
        int[] projected = levels.clone();
        projected[skill.id] = level;
        return new Stats(projected, baseLevels, xp, serverSkillCount);
    }

    /** The combat level of the base levels, by {@link AccountFacts#combatLevel(Stats)}. */
    public int combatLevel() {
        return combatLevel;
    }
}
