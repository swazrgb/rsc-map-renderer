package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Delivered only to someone on the player's friends list; otherwise the server answers "Unable to
 * send message - player not on your friendlist."
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/FriendHandler.java#L29">FriendHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1344">GameStateUpdater</a>
 */
public record PrivateMessagePacket(
        @NotNull String recipient, @NotNull String text) implements Packet {

    @Override
    public boolean cancelsOptionMenu() {
        return false;
    }

    @Override
    public boolean restable() {
        return true;
    }
}
