package openrsc.bot.api.packets;

import java.util.List;
import java.util.Set;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Replaces the player's whole stake in the open duel. The server takes at most {@value #MAX_ITEMS} offers and clears
 * both players' accepts; one sent with no duel open marks the player suspicious.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L320">PlayerDuelHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L340">PlayerDuelHandler.process</a>
 */
public record DuelOfferPacket(@NotNull List<TradeOfferPacket.Offer> offers) implements Packet {
    public static final int MAX_ITEMS = 8;

    public DuelOfferPacket {
        offers = List.copyOf(offers);
    }

    @Override
    public @NotNull Set<Extension> requires() {
        for (TradeOfferPacket.Offer o : offers) {
            if (o.noted()) {
                return Set.of(Extension.BANK_NOTES);
            }
        }
        return Set.of();
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteDuelStakeSent(offers);
    }

    @Override
    public boolean restable() {
        return false;
    }
}
