package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L322">PlayerTradeHandler.process</a>
 */
public record TradeConfirmPacket() implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
