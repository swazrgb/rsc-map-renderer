package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Fires the command1 of the boundary on edge {@code direction} of tile ({@code x}, {@code y}). The
 * server acts once the player is at it; it does not walk the player there.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectWallAction.java#L51">GameObjectWallAction.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/action/WalkToObjectAction.java#L16">WalkToObjectAction.shouldExecuteInternal</a>
 */
public record AtWallObjectPacket(int x, int y, int direction) implements Packet {

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
