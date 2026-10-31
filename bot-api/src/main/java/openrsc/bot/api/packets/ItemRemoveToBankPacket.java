package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @param wieldPosition the item's equipment slot, 0 (large helmet) to 13 (ring)
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUnequip.java#L64">ItemUnequip.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L894">Equipment.EquipmentSlot</a>
 */
@ExtendedOnly(Extension.EQUIPMENT_TAB)
public record ItemRemoveToBankPacket(int wieldPosition) implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        // The handler resets the player, which stops a running RangeEvent:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUnequip.java#L66 https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2644
        live.noteRangeKilled(now.tick());
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.EQUIPMENT_TAB);
    }
}
