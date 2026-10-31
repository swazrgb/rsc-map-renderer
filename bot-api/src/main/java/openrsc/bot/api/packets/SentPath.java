package openrsc.bot.api.packets;

import java.util.List;
import openrsc.bot.api.TickKey;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.NotNull;

/**
 * The route a walk packet carried, noted under {@link #KEY} on the tick it was sent.
 *
 * @param entity whether it was a {@link WalkToEntityPacket} rather than a {@link WalkToPointPacket}
 */
public record SentPath(@NotNull List<Point> waypoints, boolean entity) {

    public static final TickKey<SentPath> KEY = TickKey.of("walk.sent");
}
