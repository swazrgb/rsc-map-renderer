package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * {@code direction} is the boundary's edge of tile ({@code x}, {@code y}).
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUseOnObject.java#L102">ItemUseOnObject.process</a>
 */
public record UseItemOnBoundaryPacket(int x, int y, int direction, int slot) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public boolean restable() {
        return true;
    }
}
