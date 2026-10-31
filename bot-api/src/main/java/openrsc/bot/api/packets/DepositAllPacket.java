package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Needs the bank open; refused while the player is busy, ranging, trading, duelling or in combat.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L34">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L27">BankHandler.process</a>
 */
@ExtendedOnly(Extension.CUSTOM_BANKS)
public record DepositAllPacket() implements Packet {

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.CUSTOM_BANKS);
    }
}
