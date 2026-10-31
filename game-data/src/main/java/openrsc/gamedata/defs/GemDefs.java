package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemGemDef.xml}: cuttable gems by uncut item id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L193">EntityHandler.load</a>
 */
public record GemDefs(Map<Integer, GemDef> byUncutId) {

    public static GemDefs from(XmlTree root) {
        Map<Integer, GemDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(id, new GemDef(d.intOf("gemID", 0), d.intOf("requiredLvl", 0), d.intOf("exp", 0))));
        return new GemDefs(Map.copyOf(m));
    }

    public int size() {
        return byUncutId.size();
    }
}
