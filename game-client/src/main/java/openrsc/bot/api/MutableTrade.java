package openrsc.bot.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import openrsc.bot.api.container.InventorySlot;

public final class MutableTrade {
    private static final String SUCCESS_MESSAGE = "Trade completed successfully";

    private boolean open;
    private int withServerIndex = -1;
    private boolean accepted;
    private boolean otherAccepted;
    private boolean shownAccepted;
    private boolean shownOtherAccepted;
    private boolean confirmOpen;
    private List<InventorySlot> myOffer = List.of();
    private List<InventorySlot> otherOffer = List.of();
    private String confirmPartner = "";

    private List<InventorySlot> confirmMyOffer = List.of();
    private List<InventorySlot> confirmOtherOffer = List.of();
    private List<InventorySlot> gaveLast = List.of();

    private List<InventorySlot> gotLast = List.of();
    private String partnerLast = "";
    private TradeOutcome outcome = TradeOutcome.NONE;
    private int outcomeTick = Integer.MIN_VALUE;
    private int openedTick = Integer.MIN_VALUE;
    private boolean successPending;
    private boolean outcomeTickPending;

    private final LinkedHashSet<String> currentRequesters = new LinkedHashSet<>();
    private List<String> requesters = List.of();
    private boolean offerInFlight;

    private TradeView snap = TradeView.EMPTY;

    public void opened(int opponentServerIndex, int tick) {
        snap = null;
        open = true;
        withServerIndex = opponentServerIndex;
        accepted = false;
        otherAccepted = false;
        shownAccepted = false;
        shownOtherAccepted = false;
        confirmOpen = false;
        myOffer = List.of();
        otherOffer = List.of();
        confirmPartner = "";
        confirmMyOffer = List.of();
        confirmOtherOffer = List.of();
        successPending = false;
        outcomeTickPending = false;
        offerInFlight = false;
        openedTick = tick;
    }

    public void offers(List<InventorySlot> mine, List<InventorySlot> theirs) {
        snap = null;
        myOffer = List.copyOf(mine);
        otherOffer = List.copyOf(theirs);
        shownAccepted = false;
        shownOtherAccepted = false;
        if (!myOffer.isEmpty()) {
            offerInFlight = false;
        }
    }

    public void offerSentByUs() {
        snap = null;
        offerInFlight = true;
        shownAccepted = false;
        shownOtherAccepted = false;
    }

    public void accepted(boolean accepted) {
        snap = null;
        this.accepted = accepted;
        shownAccepted = accepted;
    }

    public void otherAccepted(boolean accepted) {
        snap = null;
        otherAccepted = accepted;
        shownOtherAccepted = accepted;
    }

    public void confirmOpen(boolean openNow) {
        snap = null;
        confirmOpen = openNow;
    }

    public void confirmScreen(String partner, List<InventorySlot> mine, List<InventorySlot> theirs) {
        snap = null;
        confirmOpen = true;
        confirmPartner = partner == null ? "" : partner;
        confirmMyOffer = List.copyOf(mine);
        confirmOtherOffer = List.copyOf(theirs);
    }

    public void serverMessage(String text) {
        if (open && SUCCESS_MESSAGE.equals(text)) {
            successPending = true;
        }
    }

    public void closed() {
        snap = null;
        if (open) {
            outcome = successPending ? TradeOutcome.COMPLETED : TradeOutcome.DECLINED;
            outcomeTickPending = true;
            boolean confirmed = !confirmMyOffer.isEmpty() || !confirmOtherOffer.isEmpty();
            gaveLast = !successPending ? List.of() : confirmed ? confirmMyOffer : myOffer;
            gotLast = !successPending ? List.of() : confirmed ? confirmOtherOffer : otherOffer;
            partnerLast = confirmPartner;
        }
        open = false;
        withServerIndex = -1;
        accepted = false;
        otherAccepted = false;
        shownAccepted = false;
        shownOtherAccepted = false;
        confirmOpen = false;
        myOffer = List.of();
        otherOffer = List.of();
        confirmPartner = "";
        confirmMyOffer = List.of();
        confirmOtherOffer = List.of();
        successPending = false;
        offerInFlight = false;
    }

    public void requestFrom(String username) {
        currentRequesters.add(username);
    }

    public void bundlePublished(int tick) {
        if (outcomeTickPending) {
            snap = null;
            outcomeTick = tick;
            outcomeTickPending = false;
        }
        if (currentRequesters.isEmpty()) {
            if (!requesters.isEmpty()) {
                snap = null;
                requesters = List.of();
            }
            return;
        }
        snap = null;
        requesters = List.copyOf(new ArrayList<>(currentRequesters));
        currentRequesters.clear();
    }

    public boolean open() {
        return open;
    }

    public TradeView snapshot() {
        TradeView cached = snap;
        if (cached == null) {
            cached = new TradeView(
                    open,
                    withServerIndex,
                    accepted,
                    otherAccepted,
                    confirmOpen,
                    myOffer,
                    otherOffer,
                    outcome,
                    outcomeTick,
                    openedTick,
                    requesters,
                    confirmPartner,
                    confirmMyOffer,
                    confirmOtherOffer,
                    offerInFlight,
                    gaveLast,
                    gotLast,
                    partnerLast,
                    shownAccepted,
                    shownOtherAccepted);
            snap = cached;
        }
        return cached;
    }
}
