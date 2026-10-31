package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @param ranged whether the caller expects a ranged attack. It is not sent: the server picks melee or
 *     ranged from what the player wields.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/AttackHandler.java#L92">AttackHandler.process</a>
 */
public record AttackNpcPacket(int npcServerIndex, boolean ranged) implements Packet {

    public AttackNpcPacket(int npcServerIndex) {
        this(npcServerIndex, false);
    }

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteAttackSent(now, npcServerIndex);
        if (ranged) {
            live.noteRangeArmed(npcServerIndex, now.tick());
        }
    }

    @Override
    public boolean restable() {
        return false;
    }
}
