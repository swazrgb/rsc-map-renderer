package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerFollowRequest.java#L10">PlayerFollowRequest.process</a>
 */
public record PlayerFollowPacket(int playerServerIndex) implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
