package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.NotNull;

/**
 * Lists {@code amount} of {@code itemId} for sale. Refused within 3,000 ms of the player's last
 * exchange (server clock, not ticks).
 *
 * @param totalPrice coins for the whole lot, not for each
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L323">InterfaceOptionHandler.auctionCreate</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/market/task/NewMarketItemTask.java#L35">NewMarketItemTask.doTask</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/market/task/BuyMarketItemTask.java#L52">BuyMarketItemTask.doTask</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public record AuctionCreatePacket(int itemId, int amount, int totalPrice) implements AuctionOptionPacket {

    @Override
    public int option() {
        return CREATE;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.AUCTION.requires();
    }
}
