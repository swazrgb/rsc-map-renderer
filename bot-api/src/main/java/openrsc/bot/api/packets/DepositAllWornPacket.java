package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Needs the bank open; refused while the player is busy, ranging, trading, duelling or in combat.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L34">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L27">BankHandler.process</a>
 */
@ExtendedOnly({Extension.EQUIPMENT_TAB, Extension.CUSTOM_BANKS})
public record DepositAllWornPacket() implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteRangeKilled(now.tick());
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.EQUIPMENT_TAB, Extension.CUSTOM_BANKS);
    }
}
