package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemDartTipDef.xml}: by dart tip item id, the darts it makes.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L192">EntityHandler.load</a>
 */
public record DartTipDefs(Map<Integer, DartTipDef> byTipId) {

    public static DartTipDefs from(XmlTree root) {
        Map<Integer, DartTipDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) ->
                        m.put(id, new DartTipDef(d.intOf("dartID", 0), d.intOf("requiredLvl", 0), d.intOf("exp", 0))));
        return new DartTipDefs(Map.copyOf(m));
    }

    public int size() {
        return byTipId.size();
    }
}
