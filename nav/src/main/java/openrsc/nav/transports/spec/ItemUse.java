package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ItemCommandTransport;

/**
 * Use an inventory item while standing in one of the {@code from} areas, and be moved to {@code to}:
 * an item whose handler works only in certain places. Fired from every tile of those areas that can
 * be stood on, unless {@code includeBlockedLandings}. {@code when} is the gate beyond carrying it.
 */
public record ItemUse(
        int itemId, List<Rect> from, Point to, int cost, Prereq when, boolean consumed, boolean includeBlockedLandings)
        implements TransportSpec {

    /** A use's cost, in the planner's units of 10 per walked tile. */
    public static final int COST = 10;

    public static ItemUse use(int itemId, List<Rect> from, Point to, Prereq when) {
        return new ItemUse(itemId, List.copyOf(from), to, COST, when, false, false);
    }

    /**
     * As {@link #use}, spending the item, and fired from blocked tiles of {@code from} too: tiles the
     * server teleports players onto although nobody can walk there.
     */
    public static ItemUse consumeFromLandings(int itemId, List<Rect> from, Point to, Prereq when) {
        return new ItemUse(itemId, List.copyOf(from), to, COST, when, true, true);
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        CollisionMap map = env.collisionMap();
        List<Point> entries = new ArrayList<>();
        for (Rect r : from) {
            for (int x = r.minX(); x <= r.maxX(); x++) {
                for (int z = r.minY(); z <= r.maxY(); z++) {
                    if (map.inBounds(x, z) && (includeBlockedLandings || !map.fullyBlocked(x, z))) {
                        entries.add(Point.of(x, z));
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        Prereq carried = Prereq.fact(EligibilityInputs.Fact.carriedAtLeast(itemId, 1));
        Prereq gate = when == Prereq.ALWAYS ? carried : Prereq.all(when, carried);
        return List.of(ItemCommandTransport.spendingAt(
                        itemId,
                        entries,
                        to,
                        cost,
                        gate,
                        List.of(),
                        consumed ? List.of(new Transport.Consumed(ItemId.getById(itemId), 1)) : List.of())
                .withPlacement(id));
    }
}
