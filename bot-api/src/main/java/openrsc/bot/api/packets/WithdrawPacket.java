package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Needs the bank open; refused while the player is busy, ranging, trading, duelling or in combat.
 *
 * @param noted withdraw as bank notes; a server without {@code want_bank_notes} withdraws the items
 *     themselves
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L34">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L27">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L67">BankHandler.process</a>
 */
public record WithdrawPacket(int itemId, int amount, boolean noted) implements Packet {

    public WithdrawPacket(int itemId, int amount) {
        this(itemId, amount, false);
    }

    @Override
    public boolean restable() {
        return false;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return noted ? Set.of(Extension.BANK_NOTES) : Set.of();
    }
}
