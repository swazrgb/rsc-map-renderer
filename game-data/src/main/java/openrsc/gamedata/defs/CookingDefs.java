package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemCookingDef.xml}: cookable foods by raw item id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L202">EntityHandler.load</a>
 */
public record CookingDefs(Map<Integer, CookingDef> byRawItemId) {

    public static CookingDefs from(XmlTree root) {
        Map<Integer, CookingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id,
                        new CookingDef(
                                d.intOf("requiredLvl", 0),
                                d.intOf("cookedId", 0),
                                d.intOf("burnedId", 0),
                                d.intOf("exp", 0))));
        return new CookingDefs(Map.copyOf(m));
    }

    public int size() {
        return byRawItemId.size();
    }
}
