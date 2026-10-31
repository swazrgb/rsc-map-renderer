package openrsc.bot.api.stats;

/** Skill levels being written skill by skill; read them through {@link #snapshot()}. Not thread-safe. */
public class MutableStats {

    private final int[] levels = new int[Skill.COUNT];
    private final int[] baseLevels = new int[Skill.COUNT];
    private final int[] xp = new int[Skill.COUNT];
    private int serverSkillCount = Skill.COUNT;
    private Stats snapshot;

    /** Sets how many skills the server has, which {@link Stats#totalLevel()} sums over. */
    public void setServerSkillCount(int count) {
        if (count == serverSkillCount) {
            return;
        }
        snapshot = null;
        serverSkillCount = count;
    }

    /** The current levels, frozen. The same instance is returned until the next change. */
    public Stats snapshot() {
        if (snapshot == null) {
            snapshot = new Stats(levels, baseLevels, xp, serverSkillCount);
        }

        return snapshot;
    }

    /** Sets every skill from arrays indexed by skill id; entries past {@link Skill#COUNT} are ignored. */
    public void setAll(int[] levels, int[] baseLevels, int[] xp) {
        snapshot = null;
        System.arraycopy(levels, 0, this.levels, 0, Math.min(levels.length, this.levels.length));
        System.arraycopy(baseLevels, 0, this.baseLevels, 0, Math.min(baseLevels.length, this.baseLevels.length));
        System.arraycopy(xp, 0, this.xp, 0, Math.min(xp.length, this.xp.length));
    }

    public void setSkill(int skill, int level, int baseLevel, int xp) {
        if (skill < 0 || skill >= this.levels.length) {
            return;
        }

        snapshot = null;
        this.levels[skill] = level;
        this.baseLevels[skill] = baseLevel;
        this.xp[skill] = xp;
    }

    public void setLevels(int skill, int level, int baseLevel) {
        if (skill < 0 || skill >= this.levels.length) {
            return;
        }

        snapshot = null;
        this.levels[skill] = level;
        this.baseLevels[skill] = baseLevel;
    }

    public void setXp(int skill, int xp) {
        if (skill < 0 || skill >= this.xp.length) {
            return;
        }

        snapshot = null;
        this.xp[skill] = xp;
    }
}
