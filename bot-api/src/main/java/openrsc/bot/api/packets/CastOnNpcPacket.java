package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * The server casts once the player is within 4 tiles.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1378">SpellHandler.handleMobCast</a>
 */
public record CastOnNpcPacket(int spellId, int npcServerIndex) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteCastSent(now, spellId, npcServerIndex);
    }

    @Override
    public boolean restable() {
        return true;
    }
}
