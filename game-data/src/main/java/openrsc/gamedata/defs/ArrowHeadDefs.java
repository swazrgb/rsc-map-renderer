package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemArrowHeadDef.xml}: by arrow head item id, the arrows it makes.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L196">EntityHandler.load</a>
 */
public record ArrowHeadDefs(Map<Integer, ArrowHeadDef> byHeadId) {

    public static ArrowHeadDefs from(XmlTree root) {
        Map<Integer, ArrowHeadDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id, new ArrowHeadDef(d.intOf("arrowID", 0), d.intOf("requiredLvl", 0), d.intOf("exp", 0))));
        return new ArrowHeadDefs(Map.copyOf(m));
    }

    public int size() {
        return byHeadId.size();
    }
}
