package openrsc.bot.api.packets;

import java.util.List;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.NotNull;

/**
 * In combat a walk is a retreat: refused during the opponent's first three hits, it ends the fight
 * after them.
 *
 * @param waypoints the route, at least one tile; the server walks to the first one first
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L38">WalkRequest.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L102">WalkRequest.process</a>
 */
public record WalkToPointPacket(@NotNull List<Point> waypoints) implements Packet {
    public WalkToPointPacket {
        if (waypoints.isEmpty()) {
            throw new IllegalArgumentException("walk needs at least a start tile");
        }
    }

    public WalkToPointPacket(@NotNull Point @NotNull ... waypoints) {
        this(List.of(waypoints));
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        // WalkRequest resets the player, which stops a running RangeEvent:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L94 https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2644
        live.noteRangeKilled(now.tick());
        List<Point> sent = List.copyOf(waypoints);
        live.noteWalkSent(now.tick(), sent);
        live.note(SentPath.KEY, new SentPath(sent, false));
    }

    @Override
    public boolean restable() {
        return true;
    }

    @Override
    public @NotNull Point firstStep() {
        return waypoints.getFirst();
    }
}
