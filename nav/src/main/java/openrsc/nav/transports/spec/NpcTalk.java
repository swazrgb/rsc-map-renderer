package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.NpcTalkTransport;

/**
 * Talk to an NPC, answer its menus, and be moved to a fixed tile: ships, gliders, ferrymen. The NPC
 * walks, so {@code entries} are stated tiles it can be engaged from. {@code menu} is one keyword group
 * per menu. {@code fareItem} is {@link #NO_FARE} or the item {@code fare} of which the crossing spends;
 * {@code shownItem} is the item used on the NPC instead of talking, or {@code -1}; {@code npcCommand}
 * is 0 to talk, else the NPC's command option 1 or 2.
 */
public record NpcTalk(
        int[] npcIds,
        List<Point> entries,
        int destX,
        int destZ,
        String[][] menu,
        Prereq gate,
        int cost,
        int fareItem,
        int fare,
        int shownItem,
        int npcCommand,
        String note)
        implements TransportSpec {

    public static final int NO_FARE = -1;

    public static NpcTalk talk(
            int[] npcIds, List<Point> entries, int destX, int destZ, Prereq gate, int cost, String[]... menu) {
        return new NpcTalk(npcIds, List.copyOf(entries), destX, destZ, menu, gate, cost, NO_FARE, 0, -1, 0, null);
    }

    /** The NPC's command option {@code command}, 1 or 2, instead of talking; throws on any other. */
    public static NpcTalk command(
            int[] npcIds, List<Point> entries, int destX, int destZ, Prereq gate, int cost, int command) {
        if (command != 1 && command != 2) {
            throw new IllegalArgumentException("command must be 1 or 2, got " + command);
        }
        return new NpcTalk(
                npcIds, List.copyOf(entries), destX, destZ, new String[0][], gate, cost, NO_FARE, 0, -1, command, null);
    }

    /** A paid crossing: carrying {@code fare} of {@code fareItem} is the gate, and the crossing spends it. */
    public static NpcTalk ferry(
            int[] npcIds,
            List<Point> entries,
            int destX,
            int destZ,
            int fareItem,
            int fare,
            int cost,
            String[]... menu) {
        return new NpcTalk(
                npcIds,
                List.copyOf(entries),
                destX,
                destZ,
                menu,
                Prereq.fact(Fact.carriedAtLeast(fareItem, fare)),
                cost,
                fareItem,
                fare,
                -1,
                0,
                null);
    }

    /** A {@link #ferry} the NPC offers only where {@code gate} also holds. */
    public static NpcTalk gatedFerry(
            int[] npcIds,
            List<Point> entries,
            int destX,
            int destZ,
            int fareItem,
            int fare,
            Prereq gate,
            int cost,
            String[]... menu) {
        return new NpcTalk(
                npcIds,
                List.copyOf(entries),
                destX,
                destZ,
                menu,
                Prereq.all(Prereq.fact(Fact.carriedAtLeast(fareItem, fare)), gate),
                cost,
                fareItem,
                fare,
                -1,
                0,
                null);
    }

    /** A {@link #gatedFerry} by the NPC's command option {@code command}, 1 or 2; throws on any other. */
    public static NpcTalk commandFerry(
            int[] npcIds,
            List<Point> entries,
            int destX,
            int destZ,
            int fareItem,
            int fare,
            Prereq gate,
            int cost,
            int command,
            String[]... menu) {
        if (command != 1 && command != 2) {
            throw new IllegalArgumentException("command must be 1 or 2, got " + command);
        }
        return new NpcTalk(
                npcIds,
                List.copyOf(entries),
                destX,
                destZ,
                menu,
                Prereq.all(Prereq.fact(Fact.carriedAtLeast(fareItem, fare)), gate),
                cost,
                fareItem,
                fare,
                -1,
                command,
                null);
    }

    /** Use {@code itemId} on the NPC and be moved; the item is spent. */
    public static NpcTalk spendItem(
            int[] npcIds, List<Point> entries, int destX, int destZ, int itemId, Prereq gate, int cost) {
        return new NpcTalk(
                npcIds,
                List.copyOf(entries),
                destX,
                destZ,
                new String[0][],
                Prereq.all(Prereq.fact(Fact.carriedAtLeast(itemId, 1)), gate),
                cost,
                itemId,
                1,
                itemId,
                0,
                null);
    }

    /**
     * A {@link #ferry} whose NPC searches the player before boarding and keeps them ashore while they
     * carry {@code contraband}, so it is offered only without it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/karamja/BoatFromKaramja.java#L39">BoatFromKaramja.talkToOfficer</a>
     */
    public static NpcTalk searchedFerry(
            int[] npcIds,
            List<Point> entries,
            int destX,
            int destZ,
            int fareItem,
            int fare,
            int contraband,
            int cost,
            String[]... menu) {
        return new NpcTalk(
                npcIds,
                List.copyOf(entries),
                destX,
                destZ,
                menu,
                Prereq.all(Prereq.fact(Fact.carriedAtLeast(fareItem, fare)), Prereq.fact(Fact.notCarrying(contraband))),
                cost,
                fareItem,
                fare,
                -1,
                0,
                null);
    }

    /** Use {@code itemId} on the NPC and be moved; the item is kept. */
    public static NpcTalk showItem(
            int[] npcIds, List<Point> entries, int destX, int destZ, int itemId, Prereq gate, int cost) {
        return new NpcTalk(
                npcIds,
                List.copyOf(entries),
                destX,
                destZ,
                new String[0][],
                Prereq.all(Prereq.fact(Fact.carriedAtLeast(itemId, 1)), gate),
                cost,
                NO_FARE,
                0,
                itemId,
                0,
                null);
    }

    public static int[] npcs(int... ids) {
        return ids;
    }

    public static List<Point> at(int x, int z) {
        return List.of(Point.of(x, z));
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        NpcTalkTransport t =
                new NpcTalkTransport(npcIds, entries, destX, destZ, menu, gate, cost, note).withPlacement(id);
        if (fareItem != NO_FARE) {
            t.consuming(fareItem, fare);
        }
        if (shownItem >= 0) {
            t.usingItem(shownItem);
        }
        if (npcCommand != 0) {
            t.usingCommand(npcCommand);
        }
        return List.of(t);
    }
}
