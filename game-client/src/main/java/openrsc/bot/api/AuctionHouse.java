package openrsc.bot.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import openrsc.bot.api.packets.AuctionBuyPacket;
import openrsc.bot.api.packets.AuctionCancelPacket;
import org.jetbrains.annotations.Nullable;

public record AuctionHouse(
        boolean open,
        List<Listing> listings,
        @Nullable SessionTick listedAt,
        boolean lastChunkFull,
        @Nullable SessionTick lastChunkAt,
        @Nullable Packet lastSent,
        @Nullable SessionTick lastSentAt,
        @Nullable Instant lastSentTime,
        @Nullable SessionTick optionSentAt,
        @Nullable Reply reply,
        @Nullable Boolean owed,
        boolean walkedAway) {

    public static final AuctionHouse EMPTY =
            new AuctionHouse(false, List.of(), null, false, null, null, null, null, null, null, null, false);

    public static final int CHUNK = 200;

    public AuctionHouse {
        listings = List.copyOf(listings);
    }

    public record Listing(
            int auctionId,
            int itemId,
            int amountLeft,
            int price,
            boolean mine,
            @Nullable String seller,
            int hoursLeft) {

        public int priceEach() {
            return amountLeft > 0 ? price / amountLeft : 0;
        }

        public long cost(int amount) {
            return (long) Math.min(amount, amountLeft) * priceEach();
        }
    }

    public record Reply(String text, ReplyKind kind, SessionTick at) {

        public boolean tooQuick() {
            return text.contains("acting too quickly");
        }
    }

    public enum ReplyKind {
        SUCCESS,
        WARNING,
        ERROR,
        COLLECTED,
        NOTHING_TO_COLLECT,
        HELD,
        REFUSED;

        public static @Nullable ReplyKind of(String text) {
            if (text.contains("[Auction House - Success]")) {
                return SUCCESS;
            }
            if (text.contains("[Auction House - Warning]")) {
                return WARNING;
            }
            if (text.contains("[Auction House - Error]")) {
                return ERROR;
            }
            if (text.contains("still being held by the auctioneer")) {
                return HELD;
            }
            if (text.startsWith("The following items have been sent to your bank")) {
                return COLLECTED;
            }
            if (text.equals("You have no items to collect.")) {
                return NOTHING_TO_COLLECT;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("total skill before using the auction house")
                    || lower.contains("total skill to use the auction house")
                    || lower.contains("as an ironman, you cannot use the auction")
                    || lower.contains("auction house is disabled")) {
                return REFUSED;
            }
            return null;
        }
    }

    public record Banked(int itemId, int amount) {}

    static final String BOUGHT_TO_BANK = "The item has been added to your bank.";

    static final String CANCELLED_TO_BANK = "The item has been canceled and returned to your bank.";

    public static @Nullable Banked banked(String text, @Nullable Packet sent, List<Listing> listings) {
        if (ReplyKind.of(text) != ReplyKind.SUCCESS) {
            return null;
        }
        int auctionId;
        int asked;
        if (sent instanceof AuctionBuyPacket buy && text.contains(BOUGHT_TO_BANK)) {
            auctionId = buy.auctionId();
            asked = buy.amount();
        } else if (sent instanceof AuctionCancelPacket cancel && text.contains(CANCELLED_TO_BANK)) {
            auctionId = cancel.auctionId();
            asked = Integer.MAX_VALUE;
        } else {
            return null;
        }
        for (Listing l : listings) {
            if (l.auctionId() == auctionId) {
                int amount = Math.min(asked, l.amountLeft());
                return amount > 0 ? new Banked(l.itemId(), amount) : null;
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern COLOUR = java.util.regex.Pattern.compile("@[a-z0-9]{3}@");

    public static @Nullable Boolean owes(String text) {
        return owes(text, ReplyKind.of(text));
    }

    public static @Nullable Boolean owes(String text, @Nullable ReplyKind kind) {
        if (kind == ReplyKind.HELD) {
            return true;
        }
        if (kind == ReplyKind.COLLECTED || kind == ReplyKind.NOTHING_TO_COLLECT) {
            return false;
        }
        if (!text.contains("Auction House") && !text.contains("Following items")) {
            return null;
        }
        String plain = COLOUR.matcher(text).replaceAll("").trim();
        if (plain.startsWith("Following items have been removed from market")) {
            return true;
        }
        if (plain.startsWith("[Auction House]")
                && (plain.endsWith("has been sold!") || plain.endsWith("has expired!"))) {
            return true;
        }
        return null;
    }

    public boolean complete(SessionTick now) {
        return listedAt != null && lastChunkAt != null && (!lastChunkFull || now.isAfter(lastChunkAt));
    }

    public static final int CHUNK_DUE_TICKS = 5;

    public boolean chunkOverdue(SessionTick now) {
        return listedAt != null && lastChunkAt == null && now.since(listedAt) > CHUNK_DUE_TICKS;
    }

    public int highestId() {
        int max = -1;
        for (Listing l : listings) {
            max = Math.max(max, l.auctionId());
        }
        return max;
    }

    public @Nullable Listing listedAbove(int itemId, int amount, int price, int aboveId) {
        for (Listing l : listings) {
            if (l.mine()
                    && l.auctionId() > aboveId
                    && l.itemId() == itemId
                    && l.amountLeft() == amount
                    && l.price() == price) {
                return l;
            }
        }
        return null;
    }

    public @Nullable Listing listing(int auctionId) {
        for (Listing l : listings) {
            if (l.auctionId() == auctionId) {
                return l;
            }
        }
        return null;
    }

    public List<Listing> mine() {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings) {
            if (l.mine()) {
                out.add(l);
            }
        }
        return out;
    }

    public List<Listing> of(int itemId) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings) {
            if (l.itemId() == itemId) {
                out.add(l);
            }
        }
        out.sort(java.util.Comparator.comparingInt(Listing::priceEach));
        return out;
    }

    public @Nullable Listing cheapest(int itemId) {
        for (Listing l : of(itemId)) {
            if (!l.mine()) {
                return l;
            }
        }
        return null;
    }

    public @Nullable Reply reply(ReplyKind kind) {
        return reply != null && reply.kind() == kind ? reply : null;
    }
}
