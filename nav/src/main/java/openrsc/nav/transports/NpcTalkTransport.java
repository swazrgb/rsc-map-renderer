package openrsc.nav.transports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * Talk to one of a set of NPCs from a stated tile, or use an item or a command on one, answer the
 * menus, and be moved to a fixed tile. {@code menuAnswers} holds one keyword group per menu; each
 * menu is answered by the first group that matches one of its options.
 */
public final class NpcTalkTransport implements Transport, DeclarativeTransport {

    private String name;
    private String placementId;

    private final int[] npcIds;
    private int[] serverIndexes = new int[0];

    private final List<Point> entryTiles;
    private final int destX, destY;
    private final String[][] menuAnswers;
    private final Prereq prereq;

    private final int cost;
    private final String note;
    private List<Consumed> consumes = List.of();
    private int useItemId = -1;
    private EngageSpec.Action.NpcCommand npcCommand;

    public NpcTalkTransport(
            int[] npcIds,
            List<Point> entryTiles,
            int destX,
            int destY,
            String[][] menuAnswers,
            Prereq prereq,
            int cost,
            String note) {
        this.npcIds = npcIds;
        this.entryTiles = List.copyOf(entryTiles);
        this.destX = destX;
        this.destY = destY;
        this.menuAnswers = menuAnswers;
        this.prereq = prereq;
        this.cost = cost;
        this.note = note;
    }

    public NpcTalkTransport consuming(int itemId, int count) {
        this.consumes = List.of(new Consumed(ItemId.getById(itemId), count));
        return this;
    }

    /**
     * Engages the spawns of this transport's NPC ids whose roam box, widened by {@code pad} tiles,
     * covers an entry. Does nothing once spawns are set, and leaves none set when no spawn qualifies.
     */
    public void deriveSpawns(ServerData sd, int pad) {
        if (serverIndexes.length > 0) {
            return;
        }
        List<Integer> found = new ArrayList<>();
        for (ServerData.NpcSpawn sp : sd.npcSpawns()) {
            boolean idMatch = false;
            for (int id : npcIds) {
                if (sp.id() == id) {
                    idMatch = true;
                    break;
                }
            }
            if (!idMatch) {
                continue;
            }
            for (Point e : entryTiles) {
                if (sp.within(e.x(), e.y(), pad)) {
                    found.add(sp.serverIndex());
                    break;
                }
            }
        }
        if (!found.isEmpty()) {
            this.serverIndexes = found.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    @Override
    public boolean isEntry(int x, int y) {
        for (Point t : entryTiles) {
            if (t.x() == x && t.y() == y) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return entryTiles;
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public Prereq prereq() {
        return prereq;
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return consumes;
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    /** Engages by using {@code itemId} on the NPC rather than talking to it. Returns {@code this}. */
    public NpcTalkTransport usingItem(int itemId) {
        this.useItemId = itemId;
        return this;
    }

    /** Engages by the NPC's command 1 or 2 rather than talking to it; throws on any other. */
    public NpcTalkTransport usingCommand(int command) {
        this.npcCommand = new EngageSpec.Action.NpcCommand(command);
        return this;
    }

    public NpcTalkTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }

    @Override
    public String note() {
        return note;
    }

    @Override
    public EngageSpec engageSpec() {
        if (npcCommand != null) {
            EngageSpec.Target.Npcs target = new EngageSpec.Target.Npcs(serverIndexes, npcIds);
            return menuAnswers.length == 0
                    ? EngageSpec.of(npcCommand, target)
                    : EngageSpec.of(npcCommand, target, Arrays.asList(menuAnswers));
        }
        return EngageSpec.of(
                useItemId >= 0 ? EngageSpec.Action.useItem(useItemId) : EngageSpec.Action.INTERACT_1,
                new EngageSpec.Target.Npcs(serverIndexes, npcIds),
                Arrays.asList(menuAnswers));
    }
}
