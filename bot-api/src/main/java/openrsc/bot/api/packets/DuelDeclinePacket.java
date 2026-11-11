package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Leaves the open duel, from either screen.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L309">PlayerDuelHandler.process</a>
 */
public record DuelDeclinePacket() implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
