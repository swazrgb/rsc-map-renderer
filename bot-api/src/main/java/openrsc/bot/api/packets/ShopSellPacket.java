package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @param shopAmount the stock the client shows; the server reads it but does not use it
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceShopHandler.java#L48">InterfaceShopHandler.process</a>
 */
public record ShopSellPacket(int catalogId, int shopAmount, int amount) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
