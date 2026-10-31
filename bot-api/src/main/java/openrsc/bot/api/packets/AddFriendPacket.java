package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/FriendHandler.java#L29">FriendHandler.process</a>
 */
public record AddFriendPacket(@NotNull String name) implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
