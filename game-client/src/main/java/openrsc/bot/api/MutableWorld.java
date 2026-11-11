package openrsc.bot.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import openrsc.gamedata.geometry.Rect;

public class MutableWorld {

    public static final int REMOVED = 60000;

    private final List<Npc> npcs = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private final MutableScenery scenery;
    private final MutableWalls walls = new MutableWalls();
    private final MutableGroundItems groundItems = new MutableGroundItems();

    private List<Npc> npcsView;
    private List<Player> playersView;
    private final Map<Integer, Npc> npcGhosts = new LinkedHashMap<>();
    private final Map<Integer, Npc> npcIndex = new HashMap<>();

    private final Map<Integer, Player> playerIndex = new HashMap<>();
    private List<Npc> npcGhostsView;
    private GroundItems groundItemsView;

    MutableWorld(Function<GameObject, Rect> footprint) {
        this.scenery = new MutableScenery(footprint);
    }

    public void beginNpcs() {
        npcsView = null;
        npcs.clear();
    }

    public void addNpcGhost(Npc npc) {
        npcGhostsView = null;
        npcGhosts.put(npc.serverIndex(), frozen(npc));
    }

    public void removeNpcGhost(int serverIndex) {
        if (npcGhosts.remove(serverIndex) != null) {
            npcGhostsView = null;
        }
    }

    public List<Npc> npcGhosts() {
        if (npcGhostsView == null) {
            npcGhostsView = List.copyOf(npcGhosts.values());
        }
        return npcGhostsView;
    }

    public void indexNpc(Npc npc) {
        npcIndex.put(npc.serverIndex(), npc);
    }

    public void removeNpc(Npc npc) {
        npcIndex.remove(npc.serverIndex());
        if (npcs.remove(npc)) {
            npcsView = null;
        }
    }

    public Npc npcByServerIndex(int serverIndex) {
        return npcIndex.get(serverIndex);
    }

    public void indexPlayer(Player player) {
        playerIndex.put(player.serverIndex(), player);
    }

    public void removePlayer(Player player) {
        playerIndex.remove(player.serverIndex());
        if (players.remove(player)) {
            playersView = null;
        }
    }

    public Player playerByServerIndex(int serverIndex) {
        return playerIndex.get(serverIndex);
    }

    public void addNpc(Npc npc) {
        npcsView = null;
        npcs.add(npc);
    }

    public void beginPlayers() {
        playersView = null;
        players.clear();
    }

    public void addPlayer(Player player) {
        playersView = null;
        players.add(player);
    }

    public GameObject updateObject(int id, int x, int z, int dir) {
        return scenery.update(id, x, z, dir);
    }

    public void updateWallObject(int id, int x, int z, int dir) {
        walls.update(id, x, z, dir);
    }

    public int clearChunk(int chunkX, int chunkZ) {
        return scenery.clearChunk(chunkX, chunkZ)
                + walls.clearChunk(chunkX, chunkZ)
                + groundItems.clearChunk(chunkX, chunkZ);
    }

    public void addGroundItem(int id, int x, int z, boolean noted) {
        groundItems.add(id, x, z, noted);
    }

    public boolean removeGroundItem(int id, int x, int z) {
        return groundItems.remove(id, x, z);
    }

    public void clearGroundItemsAt(int x, int z) {
        groundItems.clearAt(x, z);
    }

    public List<Npc> npcs() {
        List<Npc> view = npcsView;
        if (view != null && view.size() == npcs.size()) {
            boolean same = true;
            for (int i = 0; i < view.size(); i++) {
                if (view.get(i) != frozen(npcs.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return view;
            }
        }
        List<Npc> out = new ArrayList<>(npcs.size());
        for (Npc n : npcs) {
            out.add(frozen(n));
        }
        npcsView = Collections.unmodifiableList(out);
        return npcsView;
    }

    public List<Npc> liveNpcs() {
        return npcs;
    }

    private static Npc frozen(Npc n) {
        return n instanceof MutableNpc m ? m.snapshot() : n;
    }

    private static Player frozen(Player p) {
        return p instanceof MutablePlayer m ? m.snapshot() : p;
    }

    public List<Player> players() {
        List<Player> view = playersView;
        if (view != null && view.size() == players.size()) {
            boolean same = true;
            for (int i = 0; i < view.size(); i++) {
                if (view.get(i) != frozen(players.get(i))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return view;
            }
        }
        List<Player> out = new ArrayList<>(players.size());
        for (Player p : players) {
            out.add(frozen(p));
        }
        playersView = Collections.unmodifiableList(out);
        return playersView;
    }

    public List<Player> livePlayers() {
        return players;
    }

    public MutableScenery scenery() {
        return scenery;
    }

    public MutableWalls walls() {
        return walls;
    }

    public GroundItems groundItems() {
        ChunkMap.Frozen<List<GroundItem>> now = groundItems.freeze();
        GroundItems view = groundItemsView;
        if (view == null || view.frozen() != now) {
            view = new GroundItems(now);
            groundItemsView = view;
        }
        return view;
    }
}
