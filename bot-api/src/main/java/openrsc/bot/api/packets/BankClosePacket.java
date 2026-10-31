package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L17">BankHandler.process</a>
 */
public record BankClosePacket() implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
