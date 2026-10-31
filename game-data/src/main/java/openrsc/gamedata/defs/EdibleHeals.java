package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemEdibleHeals.xml}: hitpoints healed by eating an item, by item id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L201">EntityHandler.load</a>
 */
public record EdibleHeals(Map<Integer, Integer> byItemId) {

    public static EdibleHeals from(XmlTree root) {
        Map<Integer, Integer> m = new HashMap<>();
        ExtrasXml.eachEntry(root, (id, v) -> m.put(id, Integer.parseInt(v.text())));
        return new EdibleHeals(Map.copyOf(m));
    }

    public int size() {
        return byItemId.size();
    }
}
