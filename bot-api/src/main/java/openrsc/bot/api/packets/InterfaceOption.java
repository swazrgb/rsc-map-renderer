package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.NotNull;

/**
 * Numbered as the server's {@code InterfaceOptions}. The server refuses every option in combat, and
 * ignores one whose conf switch, {@link #requires()}, is off.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/custom/InterfaceOptions.java#L6">InterfaceOptions</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L37">InterfaceOptionHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L44">InterfaceOptionHandler.process</a>
 */
public enum InterfaceOption {
    SWAP_CERT(0, Extension.CERT_DEPOSIT),
    SWAP_NOTE(1, Extension.BANK_NOTES),
    BANK_SWAP(2, Extension.CUSTOM_BANKS),
    BANK_INSERT(3, Extension.CUSTOM_BANKS),
    INVENTORY_INSERT(4, Extension.CUSTOM_BANKS),
    INVENTORY_SWAP(5, Extension.CUSTOM_BANKS),
    CANCEL_BATCH(6, Extension.BATCHING),
    IRONMAN_MODE(7, Extension.IRONMAN),
    AUCTION(10, Extension.AUCTION_HOUSE);

    private final int id;
    private final Extension extension;

    InterfaceOption(int id, Extension extension) {
        this.id = id;
        this.extension = extension;
    }

    public int id() {
        return id;
    }

    public @NotNull Set<Extension> requires() {
        return Set.of(extension);
    }
}
