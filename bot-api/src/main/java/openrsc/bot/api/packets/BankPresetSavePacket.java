package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Saves the player's inventory and equipment. Needs the bank open.
 *
 * @param presetSlot 0 or 1
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L136">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L34">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/BankPreset.java#L18">BankPreset.PRESET_COUNT</a>
 */
@ExtendedOnly({Extension.EQUIPMENT_TAB, Extension.BANK_PRESETS, Extension.CUSTOM_BANKS})
public record BankPresetSavePacket(int presetSlot) implements Packet {

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.EQUIPMENT_TAB, Extension.BANK_PRESETS, Extension.CUSTOM_BANKS);
    }
}
