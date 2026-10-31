package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.NotNull;

/**
 * Clears the player's {@code auctionhouse} attribute.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L298">InterfaceOptionHandler.handleAuction</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public record AuctionClosePacket() implements AuctionOptionPacket {

    @Override
    public int option() {
        return CLOSE;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.AUCTION.requires();
    }
}
