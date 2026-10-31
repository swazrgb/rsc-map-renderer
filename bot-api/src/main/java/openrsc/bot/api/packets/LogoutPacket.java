package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Refused in combat or a duel, while busy, or soon after fighting.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/LogoutRequest.java#L12">LogoutRequest.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L818">Player.canLogout</a>
 */
public record LogoutPacket() implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
