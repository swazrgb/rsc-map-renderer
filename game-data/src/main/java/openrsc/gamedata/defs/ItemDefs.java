package openrsc.gamedata.defs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import openrsc.gamedata.data.ItemId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Items by id: the server's {@code ItemDefs.json}, then {@code ItemDefsCustom.json}. Ids without a
 * def answer {@code null} from {@link #get}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L173">EntityHandler.load</a>
 */
public final class ItemDefs {

    private final ItemDef[] byId;
    private final Set<String> ambiguousNames;

    private ItemDefs(ItemDef[] byId) {
        this.byId = byId;
        this.ambiguousNames = computeAmbiguousNames(byId);
    }

    /** Defs indexed by id, {@code null} where there is none; the array is used as is, not copied. */
    public static ItemDefs of(ItemDef[] byId) {
        return new ItemDefs(byId);
    }

    /**
     * Reads {@code baseFile}, then {@code customFile} when it exists; a custom def replaces a base def
     * of the same id.
     *
     * @throws IOException when a file cannot be read or lacks its array of items
     */
    public static ItemDefs load(Path baseFile, Path customFile) throws IOException {
        ObjectMapper m = new ObjectMapper();
        List<ItemDef> all = new ArrayList<>(readArray(m, baseFile, "item"));
        if (customFile != null && Files.exists(customFile)) {
            all.addAll(readArray(m, customFile, "items"));
        }
        int max = 0;
        for (ItemDef e : all) {
            if (e.id() > max) {
                max = e.id();
            }
        }
        ItemDef[] arr = new ItemDef[max + 1];
        for (ItemDef e : all) {
            arr[e.id()] = e;
        }
        return new ItemDefs(arr);
    }

    public ItemDef get(int id) {
        return (id >= 0 && id < byId.length) ? byId[id] : null;
    }

    /** One more than the highest id. */
    public int size() {
        return byId.length;
    }

    public boolean isStackable(int id) {
        ItemDef e = get(id);
        return e != null && e.stackable();
    }

    /**
     * A name that tells this item apart: the def's, unless other items share it (the unidentified
     * herbs are all "Herb"), then the {@link ItemId} constant's. Never {@code null}.
     */
    public String nameOf(int id) {
        ItemDef e = get(id);
        String name = e == null ? null : e.name();
        if (name == null || ambiguousNames.contains(name)) {
            ItemId enumId = ItemId.getById(id);
            if (enumId != null && enumId != ItemId.NOTHING && enumId.id() == id) {
                return enumId.displayName();
            }
        }
        return name != null ? name : ItemId.nameOf(id);
    }

    private static Set<String> computeAmbiguousNames(ItemDef[] byId) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemDef e : byId) {
            if (e != null && e.name() != null) {
                counts.merge(e.name(), 1, Integer::sum);
            }
        }
        Set<String> dup = new HashSet<>();
        for (Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                dup.add(e.getKey());
            }
        }
        return dup;
    }

    private static List<ItemDef> readArray(ObjectMapper m, Path file, String rootKey) throws IOException {
        JsonNode root = m.readTree(Files.readAllBytes(file));
        JsonNode arr = root.path(rootKey);
        if (!arr.isArray()) {
            throw new IOException("no \"" + rootKey + "\" array in " + file);
        }
        List<ItemDef> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(m.treeToValue(n, ItemDef.class));
        }
        return out;
    }
}
