package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GroundItemTake.java#L17">GroundItemTake.process</a>
 */
public record PickupGroundItemPacket(int x, int y, int itemId) implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        // Taking an item resets the player, which stops a running RangeEvent:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GroundItemTake.java#L31
        live.noteRangeKilled(now.tick());
    }

    @Override
    public boolean restable() {
        return true;
    }

    @Override
    public boolean installsWalkToAction() {
        return true;
    }
}
