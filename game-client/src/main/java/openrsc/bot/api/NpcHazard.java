package openrsc.bot.api;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerRules.NpcBlocking;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.walk.Berth;

public final class NpcHazard {

    static final int RADIUS = Berth.RADIUS;

    public static final int BERTH_COST = Berth.BERTH_COST;

    public static final NpcHazard EMPTY = new NpcHazard(Berth.EMPTY, List.of(), 0);

    private final Berth berth;

    private final List<Npc> sightings;

    private final int combatLevel;

    private NpcHazard(Berth berth, List<Npc> sightings, int combatLevel) {
        this.berth = berth;
        this.sightings = sightings;
        this.combatLevel = combatLevel;
    }

    public boolean blocks(int x, int y) {
        return berth.blocks(x, y);
    }

    public int berth(int x, int y) {
        return berth.cost(x, y);
    }

    /**
     * Whether an aggressor in view or remembered out of it reaches {@code (x, y)} with its rings, for this account
     * standing there: unlike {@link #berth(int, int)}, however far the tile lies.
     */
    public boolean ringsReach(int x, int y) {
        return Berth.ringsReach(sightings, x, y, combatLevel);
    }

    static NpcHazard build(Point pos, List<Npc> npcs, List<Npc> npcGhosts, int combatLevel, NpcBlocking blocking) {
        if (npcs.isEmpty() && npcGhosts.isEmpty()) {
            return EMPTY;
        }
        List<Npc> all = npcs;
        if (!npcGhosts.isEmpty()) {
            all = new ArrayList<>(npcs.size() + npcGhosts.size());
            all.addAll(npcs);
            all.addAll(npcGhosts);
        }
        return new NpcHazard(Berth.build(pos, all, combatLevel, blocking), all, combatLevel);
    }
}
