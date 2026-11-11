package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L183">PlayerTradeHandler.process</a>
 */
public record TradeAcceptFirstPacket() implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteTradeAcceptSent();
    }

    @Override
    public boolean restable() {
        return false;
    }
}
