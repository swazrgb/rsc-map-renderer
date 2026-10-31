package openrsc.gamedata.defs;

import java.util.Locale;
import openrsc.gamedata.data.NpcId;

/** An NPC, as the server's {@code NpcDefs.json} has it; {@code respawnTime} is in seconds. */
public record NpcDef(
        int id,
        String name,
        int combatLevel,
        int hits,
        boolean attackable,
        boolean aggressive,
        int respawnTime,
        String description,
        int attack,
        int strength,
        int defense,
        boolean ranged,
        boolean members) {

    /**
     * The most damage one melee hit from this NPC can do, from its Strength, with no weapon, prayer or
     * style bonus. 0 when it has no Strength.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/combat/CombatFormula.java#L34">CombatFormula.calculateMeleeDamage</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/combat/CombatFormula.java#L229">CombatFormula.getMeleeDamage</a>
     */
    public int maxHit() {
        int maxRoll = strength * 64;
        return maxRoll <= 0 ? 0 : (maxRoll - 1 + 320) / 640;
    }

    /**
     * Whether this NPC's hits can poison a player: one named "poison", a dungeon spider, a tribesman or a
     * jungle savage.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/combat/scripts/all/NpcPoisonPlayerScript.java#L26">NpcPoisonPlayerScript.shouldExecute</a>
     */
    public boolean poisons() {
        return (name != null && name.toLowerCase(Locale.ROOT).contains("poison"))
                || id == NpcId.DUNGEON_SPIDER.id()
                || id == NpcId.TRIBESMAN.id()
                || id == NpcId.JUNGLE_SAVAGE.id();
    }
}
