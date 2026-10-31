package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PrayerHandler.java#L74">PrayerHandler.process</a>
 */
public record PrayerDeactivatedPacket(int prayerId) implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.notePrayerSent(prayerId, false);
    }

    @Override
    public boolean restable() {
        return true;
    }
}
