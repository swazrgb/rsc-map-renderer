package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Accepts the duel's confirm screen; once both players have, the duel starts.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L164">PlayerDuelHandler.process</a>
 */
public record DuelConfirmPacket() implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
