package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Swaps the bank's stacks in slots {@code slot} and {@code to}, both counted from 0; ignored unless both hold a stack
 * and differ.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L100-L110">InterfaceOptionHandler.handleBankSwap</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L314-L336">Bank.swap</a>
 */
@ExtendedOnly(Extension.CUSTOM_BANKS)
public record BankSwapPacket(int slot, int to) implements Packet {
    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.BANK_SWAP.requires();
    }
}
