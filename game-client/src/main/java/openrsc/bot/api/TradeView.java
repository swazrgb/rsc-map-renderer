package openrsc.bot.api;

import java.util.List;
import openrsc.bot.api.container.InventorySlot;

/**
 * The trade screen. {@code accepted} and {@code otherAccepted} follow the server's packets alone;
 * {@code shownAccepted} and {@code shownOtherAccepted} are the accepts as the stock client shows them, which an offer
 * either way clears, though the server sends an offer's items after an accept made the same tick.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/PacketHandler.java#L2591-L2592">PacketHandler, the other's offer</a>
 */
public record TradeView(
        boolean open,
        int withServerIndex,
        boolean accepted,
        boolean otherAccepted,
        boolean confirmOpen,
        List<InventorySlot> myOffer,
        List<InventorySlot> otherOffer,
        TradeOutcome outcome,
        int outcomeTick,
        int openedTick,
        List<String> requesters,
        String confirmPartner,
        List<InventorySlot> confirmMyOffer,
        List<InventorySlot> confirmOtherOffer,
        boolean offerInFlight,
        List<InventorySlot> gaveLast,
        List<InventorySlot> gotLast,
        String partnerLast,
        boolean shownAccepted,
        boolean shownOtherAccepted) {

    public static final TradeView EMPTY = new TradeView(
            false,
            -1,
            false,
            false,
            false,
            List.of(),
            List.of(),
            TradeOutcome.NONE,
            Integer.MIN_VALUE,
            Integer.MIN_VALUE,
            List.of(),
            "",
            List.of(),
            List.of(),
            false,
            List.of(),
            List.of(),
            "",
            false,
            false);

    public TradeView {
        myOffer = List.copyOf(myOffer);
        otherOffer = List.copyOf(otherOffer);
        requesters = List.copyOf(requesters);
        confirmMyOffer = List.copyOf(confirmMyOffer);
        confirmOtherOffer = List.copyOf(confirmOtherOffer);
        gaveLast = List.copyOf(gaveLast);
        gotLast = List.copyOf(gotLast);
        confirmPartner = confirmPartner == null ? "" : confirmPartner;
        partnerLast = partnerLast == null ? "" : partnerLast;
    }

    public int openTicks(int serverTickNow) {
        return open ? Math.max(0, serverTickNow - openedTick) : 0;
    }
}
