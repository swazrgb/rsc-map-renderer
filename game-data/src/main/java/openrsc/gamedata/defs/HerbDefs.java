package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemHerbDef.xml}: by clean herb item id, the unfinished potion it makes.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L200">EntityHandler.load</a>
 */
public record HerbDefs(Map<Integer, HerbDef> byHerbId) {

    public static HerbDefs from(XmlTree root) {
        Map<Integer, HerbDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) ->
                        m.put(id, new HerbDef(d.intOf("potionId", 0), d.intOf("requiredLvl", 0), d.intOf("exp", 0))));
        return new HerbDefs(Map.copyOf(m));
    }

    public int size() {
        return byHerbId.size();
    }
}
