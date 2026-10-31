package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

/**
 * Uses a worn item on a boundary, as {@link UseItemOnBoundaryPacket} uses an inventory item; the
 * server finds the item in the equipment by id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUseOnObject.java#L110">ItemUseOnObject.process</a>
 */
@ExtendedOnly(Extension.EQUIPMENT_TAB)
public record UseWornItemOnBoundaryPacket(int x, int y, int direction, int itemId) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public boolean restable() {
        return true;
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.EQUIPMENT_TAB);
    }
}
