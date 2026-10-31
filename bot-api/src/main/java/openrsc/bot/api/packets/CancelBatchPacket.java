package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Interrupts the player's running plugins, which stops a skilling batch.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L71">InterfaceOptionHandler.process</a>
 */
@ExtendedOnly(Extension.BATCHING)
public record CancelBatchPacket() implements Packet {

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.CANCEL_BATCH.requires();
    }
}
