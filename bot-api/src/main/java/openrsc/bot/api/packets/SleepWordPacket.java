package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Ignored unless the player is asleep.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SleepHandler.java#L16">SleepHandler.process</a>
 */
public record SleepWordPacket(@NotNull String word) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
