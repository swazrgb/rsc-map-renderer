package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Moves the bank's stack in slot {@code slot} to slot {@code to}, both counted from 0, the stacks between them shifting
 * one slot over; ignored unless both slots hold a stack and differ. The server sends the whole bank again.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L112-L118">InterfaceOptionHandler.handleBankInsert</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L339">Bank.insert</a>
 */
@ExtendedOnly(Extension.CUSTOM_BANKS)
public record BankInsertPacket(int slot, int to) implements Packet {
    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.BANK_INSERT.requires();
    }
}
