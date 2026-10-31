package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemUnIdentHerbDef.xml}: by unidentified herb item id, the herb it identifies as.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L199">EntityHandler.load</a>
 */
public record UnIdentHerbDefs(Map<Integer, UnIdentHerbDef> byUnidentId) {

    public static UnIdentHerbDefs from(XmlTree root) {
        Map<Integer, UnIdentHerbDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id, new UnIdentHerbDef(d.intOf("requiredLvl", 0), d.intOf("newId", 0), d.intOf("exp", 0))));
        return new UnIdentHerbDefs(Map.copyOf(m));
    }

    public int size() {
        return byUnidentId.size();
    }
}
