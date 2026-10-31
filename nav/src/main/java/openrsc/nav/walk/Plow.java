package openrsc.nav.walk;

import openrsc.gamedata.defs.NpcDef;

/**
 * When a walk may attack the NPC that body-blocks its only way on. The attacker is moved onto its
 * victim's tile, so the fight carries the walk past the block, and walking on is a retreat, which
 * the server allows once the opponent has struck three times.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L799">Mob.startCombat</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L37">WalkRequest.process</a>
 */
public final class Plow {

    /** Ticks a sealed way is waited out, in case the blocker moves off, before it is attacked. */
    public static final int PATIENCE = 10;

    private Plow() {}

    /**
     * Whether a blocker of {@code def} may be attacked: it is attackable and not already fighting, and
     * {@code canEngage} says it can be reached from where the walker stands.
     */
    public static boolean plowable(NpcDef def, boolean inCombat, boolean canEngage) {
        return def != null && def.attackable() && !inCombat && canEngage;
    }
}
