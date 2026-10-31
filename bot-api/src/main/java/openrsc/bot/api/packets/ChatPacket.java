package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ChatHandler.java#L16">ChatHandler.process</a>
 */
public record ChatPacket(@NotNull String text) implements Packet {

    @Override
    public boolean cancelsOptionMenu() {
        return false;
    }

    @Override
    public boolean restable() {
        return true;
    }
}
