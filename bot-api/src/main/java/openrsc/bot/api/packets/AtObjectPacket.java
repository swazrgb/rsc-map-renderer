package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.NotNull;

/**
 * Fires the command1 of the scenery on tile ({@code x}, {@code y}): the server finds it by tile, not
 * by id, and acts once the player is at it. It does not walk the player there.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectAction.java#L55">GameObjectAction.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/action/WalkToObjectAction.java#L16">WalkToObjectAction.shouldExecuteInternal</a>
 */
public record AtObjectPacket(int x, int y) implements Packet {
    public AtObjectPacket(@NotNull Point tile) {
        this(tile.x(), tile.y());
    }

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteEngageSent(now.tick());
    }

    @Override
    public boolean restable() {
        return true;
    }
}
