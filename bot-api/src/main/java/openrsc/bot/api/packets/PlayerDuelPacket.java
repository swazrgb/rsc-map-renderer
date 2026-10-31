package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L30">PlayerDuelHandler.process</a>
 */
public record PlayerDuelPacket(int playerServerIndex) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public boolean restable() {
        return false;
    }
}
