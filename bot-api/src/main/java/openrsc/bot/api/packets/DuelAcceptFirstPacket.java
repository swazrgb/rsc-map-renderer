package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Accepts the open duel's stakes and rules; once both players have, the server opens the confirm screen.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L146">PlayerDuelHandler.process</a>
 */
public record DuelAcceptFirstPacket() implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
