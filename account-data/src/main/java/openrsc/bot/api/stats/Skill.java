package openrsc.bot.api.stats;

/**
 * The skills, by the server's skill id on a world without the custom skill set. Runecraft and
 * Harvesting exist only where the world turns them on; elsewhere their ids read as 0.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Skills.java#L250">Skills.Skills</a>
 */
public enum Skill {
    ATTACK(0),
    DEFENSE(1),
    STRENGTH(2),
    HITS(3),
    RANGED(4),
    PRAYER(5),
    MAGIC(6),
    COOKING(7),
    WOODCUTTING(8),
    FLETCHING(9),
    FISHING(10),
    FIREMAKING(11),
    CRAFTING(12),
    SMITHING(13),
    MINING(14),
    HERBLAW(15),
    AGILITY(16),
    THIEVING(17),
    RUNECRAFT(18),
    HARVESTING(19);

    private static final Skill[] VALUES = values();

    public static final int COUNT = VALUES.length;

    public final int id;

    Skill(int id) {
        this.id = id;
    }
}
