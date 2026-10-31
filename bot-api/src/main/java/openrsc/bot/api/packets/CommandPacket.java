package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Refused within 1,000 ms of the player's last command, unless the player is an admin.
 *
 * @param command the command text, without the leading {@code ::}
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/CommandHandler.java#L13">CommandHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/commands/RegularPlayer.java#L106">RegularPlayer.onCommand</a>
 */
public record CommandPacket(@NotNull String command) implements Packet {

    /**
     * Opens the bank from inside one when {@code right_click_bank} is on.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/commands/RegularPlayer.java#L106">RegularPlayer.onCommand</a>
     */
    public static final String QUICK_BANK = "b";

    @Override
    public boolean restable() {
        return true;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        int newline = command.indexOf('\n');
        String text = newline < 0 ? command : command.substring(0, newline);
        int space = text.indexOf(' ');
        String name = space < 0 ? text : text.substring(0, space).trim();
        return name.equalsIgnoreCase(QUICK_BANK) ? Set.of(Extension.QUICK_BANK) : Set.of();
    }
}
