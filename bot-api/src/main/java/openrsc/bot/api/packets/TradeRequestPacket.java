package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Also accepts the other player's request: the trade opens once both have asked. Refused beyond 4
 * tiles or without a clear path.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L98">PlayerTradeHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L104">PlayerTradeHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L115">PlayerTradeHandler.process</a>
 */
public record TradeRequestPacket(int playerServerIndex) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
