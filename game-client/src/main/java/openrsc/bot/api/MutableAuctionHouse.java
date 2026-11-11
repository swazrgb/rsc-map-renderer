package openrsc.bot.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.AuctionHouse.Listing;
import openrsc.bot.api.AuctionHouse.Reply;
import openrsc.bot.api.AuctionHouse.ReplyKind;
import openrsc.bot.api.packets.AuctionCollectPacket;
import openrsc.bot.api.packets.AuctionOpenPacket;
import openrsc.bot.api.packets.AuctionOptionPacket;
import openrsc.bot.api.packets.WalkToEntityPacket;
import openrsc.bot.api.packets.WalkToPointPacket;
import org.jetbrains.annotations.Nullable;

public final class MutableAuctionHouse {

    private boolean open;
    private final List<Listing> listings = new ArrayList<>();
    private @Nullable SessionTick listedAt;
    private boolean lastChunkFull;
    private @Nullable SessionTick lastChunkAt;
    private @Nullable Packet lastSent;
    private @Nullable SessionTick lastSentAt;
    private @Nullable Instant lastSentTime;
    private @Nullable SessionTick optionSentAt;
    private @Nullable Reply reply;
    private @Nullable Boolean owed;
    private boolean walkedAway;
    private @Nullable AuctionHouse snap = AuctionHouse.EMPTY;

    public void loggedIn(SessionTick first) {
        snap = null;
        optionSentAt = first;
    }

    public void listCleared(SessionTick now) {
        snap = null;
        open = true;
        listings.clear();
        listedAt = now;
        lastChunkFull = false;
        lastChunkAt = null;
    }

    public void listed(List<Listing> chunk, SessionTick now) {
        snap = null;
        open = true;
        listings.addAll(chunk);
        lastChunkFull = chunk.size() >= AuctionHouse.CHUNK;
        lastChunkAt = now;
    }

    public void sent(Packet packet, SessionTick now, @Nullable Instant at) {
        boolean auction = packet instanceof AuctionOptionPacket
                || packet instanceof AuctionOpenPacket
                || packet instanceof AuctionCollectPacket;
        if (!auction
                && (packet instanceof WalkToPointPacket || packet instanceof WalkToEntityPacket)
                && lastSent != null
                && reply == null
                && !walkedAway) {
            snap = null;
            walkedAway = true;
        }
        if (!open && !auction) {
            return;
        }
        snap = null;
        open = false;
        if (auction) {
            lastSent = packet;
            lastSentAt = now;
            lastSentTime = at;
            reply = null;
            walkedAway = false;
            if (packet instanceof AuctionOptionPacket) {
                optionSentAt = now;
            }
        }
    }

    public void fought() {
        if (lastSent != null && reply == null && !walkedAway) {
            snap = null;
            walkedAway = true;
        }
    }

    public AuctionHouse.@Nullable Banked said(String text, SessionTick now) {
        ReplyKind kind = ReplyKind.of(text);
        Boolean owes = AuctionHouse.owes(text, kind);
        if (kind == null && owes == null) {
            return null;
        }
        snap = null;
        AuctionHouse.Banked banked = null;
        if (kind != null) {
            if (reply == null) {
                banked = AuctionHouse.banked(text, lastSent, listings);
            }
            reply = new Reply(text, kind, now);
        }
        if (owes != null) {
            owed = owes;
        }
        return banked;
    }

    public AuctionHouse snapshot() {
        AuctionHouse s = snap;
        if (s == null) {
            s = new AuctionHouse(
                    open,
                    listings,
                    listedAt,
                    lastChunkFull,
                    lastChunkAt,
                    lastSent,
                    lastSentAt,
                    lastSentTime,
                    optionSentAt,
                    reply,
                    owed,
                    walkedAway);
            snap = s;
        }
        return s;
    }
}
