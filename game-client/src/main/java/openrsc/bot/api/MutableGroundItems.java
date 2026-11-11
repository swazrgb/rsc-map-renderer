package openrsc.bot.api;

import java.util.ArrayList;
import java.util.List;

public final class MutableGroundItems {

    private final ChunkMap<List<GroundItem>> items = new ChunkMap<>(1);

    void add(int id, int x, int z, boolean noted) {
        List<GroundItem> here = items.get(x, z, 0);
        List<GroundItem> next = new ArrayList<>(here == null ? 1 : here.size() + 1);
        if (here != null) {
            next.addAll(here);
        }
        next.add(new GroundItem(id, x, z, noted));
        items.put(x, z, 0, List.copyOf(next));
    }

    boolean remove(int id, int x, int z) {
        List<GroundItem> here = items.get(x, z, 0);
        if (here == null) {
            return false;
        }
        for (int i = 0; i < here.size(); i++) {
            if (here.get(i).id() == id) {
                if (here.size() == 1) {
                    items.remove(x, z, 0);
                } else {
                    List<GroundItem> next = new ArrayList<>(here);
                    next.remove(i);
                    items.put(x, z, 0, List.copyOf(next));
                }
                return true;
            }
        }
        return false;
    }

    void clearAt(int x, int z) {
        items.remove(x, z, 0);
    }

    int clearChunk(int cx, int cz) {
        int n = 0;
        for (List<GroundItem> here : items.clearChunk(cx, cz)) {
            n += here.size();
        }
        return n;
    }

    ChunkMap.Frozen<List<GroundItem>> freeze() {
        return items.freeze();
    }
}
