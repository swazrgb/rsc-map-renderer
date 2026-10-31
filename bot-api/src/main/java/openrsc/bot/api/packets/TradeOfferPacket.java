package openrsc.bot.api.packets;

import java.util.List;
import java.util.Set;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Replaces the player's whole side of the open trade. The server takes at most {@value #MAX_ITEMS}
 * offers and clears both players' accepts; an offer sent after both have accepted cancels the trade.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L246">PlayerTradeHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L248">PlayerTradeHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L233">PlayerTradeHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L225">PlayerTradeHandler.process</a>
 */
public record TradeOfferPacket(@NotNull List<Offer> offers) implements Packet {
    public static final int MAX_ITEMS = 12;

    public record Offer(int itemId, int amount, boolean noted) {

        public Offer(int itemId, int amount) {
            this(itemId, amount, false);
        }
    }

    @Override
    public @NotNull Set<Extension> requires() {
        for (Offer o : offers) {
            if (o.noted()) {
                return Set.of(Extension.BANK_NOTES);
            }
        }
        return Set.of();
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteTradeOfferSent();
    }

    @Override
    public boolean restable() {
        return false;
    }
}
