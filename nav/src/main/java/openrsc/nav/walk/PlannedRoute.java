package openrsc.nav.walk;

import openrsc.bot.api.TickKey;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Route;

/**
 * A route as the planner gave it, before any tick re-guided its walk ({@link RouteKeeper#kept()}),
 * with the tile it was planned from; published as a tick note under {@link #KEY}.
 */
public record PlannedRoute(Point from, Route route) {

    public static final TickKey<PlannedRoute> KEY = TickKey.of("walk.planned");
}
