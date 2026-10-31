package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.NotNull;

/**
 * Allowed once per 5,000 ms (server clock, not ticks).
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L340">InterfaceOptionHandler.auctionRefresh</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public record AuctionRefreshPacket() implements AuctionOptionPacket {

    @Override
    public int option() {
        return REFRESH;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.AUCTION.requires();
    }
}
