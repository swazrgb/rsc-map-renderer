package openrsc.bot.api.packets;

import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;

/**
 * An auction house option, sent as the {@link InterfaceOption#AUCTION} interface option.
 *
 * <p>The server ignores each one unless the auction window is open: the player's
 * {@code auctionhouse} attribute, which {@link AuctionOpenPacket} sets and {@code Player.resetAll}
 * clears. It refuses each one below 100 total level, and every one it handles stamps the player's
 * exchange time.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L271">InterfaceOptionHandler.handleAuction</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2600">Player.resetAll</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L275">InterfaceOptionHandler.handleAuction</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L305">InterfaceOptionHandler.handleAuction</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public sealed interface AuctionOptionPacket extends Packet
        permits AuctionBuyPacket, AuctionCreatePacket, AuctionCancelPacket, AuctionRefreshPacket, AuctionClosePacket {

    int BUY = 0;

    int CREATE = 1;

    int ABORT = 2;

    int REFRESH = 3;

    int CLOSE = 4;

    /**
     * Numbered as the server's {@code AuctionOptions}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/custom/AuctionOptions.java#L6">AuctionOptions</a>
     */
    int option();
}
