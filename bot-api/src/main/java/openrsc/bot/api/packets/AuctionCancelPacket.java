package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.NotNull;

/**
 * Refused within 3,000 ms of the player's last exchange (server clock, not ticks).
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L332">InterfaceOptionHandler.auctionCancel</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/market/task/CancelMarketItemTask.java#L27">CancelMarketItemTask.doTask</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public record AuctionCancelPacket(int auctionId) implements AuctionOptionPacket {

    @Override
    public int option() {
        return ABORT;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.AUCTION.requires();
    }
}
