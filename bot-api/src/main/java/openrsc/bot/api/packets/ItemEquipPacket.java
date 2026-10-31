package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemEquip.java#L12">ItemEquip.process</a>
 */
public record ItemEquipPacket(int slot) implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        // The handler resets the player, which stops a running RangeEvent:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemEquip.java#L30 https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2644
        live.noteRangeKilled(now.tick());
    }

    @Override
    public boolean restable() {
        return true;
    }
}
