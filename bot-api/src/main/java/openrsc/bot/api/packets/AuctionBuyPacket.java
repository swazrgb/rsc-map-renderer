package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.NotNull;

/**
 * Refused within 3,000 ms of the player's last exchange (server clock, not ticks). {@code amount}
 * is capped at what is left of the listing, and each unit costs the listing's price divided by what
 * is left, rounded down.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L311">InterfaceOptionHandler.auctionBuyItem</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/market/task/BuyMarketItemTask.java#L48">BuyMarketItemTask.doTask</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/market/task/BuyMarketItemTask.java#L52">BuyMarketItemTask.doTask</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public record AuctionBuyPacket(int auctionId, int amount) implements AuctionOptionPacket {

    @Override
    public int option() {
        return BUY;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.AUCTION.requires();
    }
}
