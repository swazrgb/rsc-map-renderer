package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * The server caps {@code amount} at the shop's stock.
 *
 * @param shopAmount the stock the client shows; the server reads it but does not use it
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceShopHandler.java#L48">InterfaceShopHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceShopHandler.java#L85">InterfaceShopHandler.buyShopItem</a>
 */
public record ShopBuyPacket(int catalogId, int shopAmount, int amount) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
