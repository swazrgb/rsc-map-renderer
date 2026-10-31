package openrsc.bot.api.packets;

import java.util.List;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.NotNull;

/**
 * The walk sent in the same tick just before a click on an NPC or player. The server handles it as
 * it does {@link WalkToPointPacket}, except that in combat it ignores it. A click handler's
 * {@code Player.resetAll} keeps the walking queue, so the player follows this route rather than the
 * server's own {@code Mob.walkToEntity}.
 *
 * @param waypoints the route, at least one tile; the server walks to the first one first
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L91">WalkRequest.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2587">Player.resetAll</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L715">Mob.walkToEntity</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L102">WalkRequest.process</a>
 */
public record WalkToEntityPacket(@NotNull List<Point> waypoints) implements Packet {
    public WalkToEntityPacket {
        if (waypoints.isEmpty()) {
            throw new IllegalArgumentException("walk needs at least a start tile");
        }
    }

    public WalkToEntityPacket(@NotNull Point @NotNull ... waypoints) {
        this(List.of(waypoints));
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.note(SentPath.KEY, new SentPath(List.copyOf(waypoints), true));
    }

    @Override
    public boolean restable() {
        return true;
    }
}
