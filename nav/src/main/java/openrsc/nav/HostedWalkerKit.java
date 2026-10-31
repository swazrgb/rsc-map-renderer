package openrsc.nav;

import java.util.List;
import openrsc.gamedata.ServerData;

/**
 * What a route follower needs from one {@link Pathfinder}: its planner, the transport registry its
 * routes name hops from, and the world it plans on. Built once per pathfinder and shared by every
 * follower on that world.
 */
public record HostedWalkerKit(Planner nav, List<Transport> registry, ServerData env) {}
