package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUseOnItem.java#L13">ItemUseOnItem.process</a>
 */
public record UseItemOnItemPacket(int slot, int targetSlot) implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
