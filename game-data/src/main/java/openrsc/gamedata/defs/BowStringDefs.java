package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemBowStringDef.xml}: by unstrung bow item id, the bow it strings into.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L195">EntityHandler.load</a>
 */
public record BowStringDefs(Map<Integer, BowStringDef> byUnstrungId) {

    public static BowStringDefs from(XmlTree root) {
        Map<Integer, BowStringDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) ->
                        m.put(id, new BowStringDef(d.intOf("bowID", 0), d.intOf("requiredLvl", 0), d.intOf("exp", 0))));
        return new BowStringDefs(Map.copyOf(m));
    }

    public int size() {
        return byUnstrungId.size();
    }
}
