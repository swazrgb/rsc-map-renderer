package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemLogCutDef.xml}: by log item id, what a knife cuts it into.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L194">EntityHandler.load</a>
 */
public record LogCutDefs(Map<Integer, LogCutDef> byLogId) {

    public static LogCutDefs from(XmlTree root) {
        Map<Integer, LogCutDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id,
                        new LogCutDef(
                                d.intOf("shaftAmount", 0),
                                d.intOf("shaftLvl", 0),
                                d.intOf("shortbowID", 0),
                                d.intOf("shortbowLvl", 0),
                                d.intOf("shortbowExp", 0),
                                d.intOf("longbowID", 0),
                                d.intOf("longbowLvl", 0),
                                d.intOf("longbowExp", 0))));
        return new LogCutDefs(Map.copyOf(m));
    }

    public int size() {
        return byLogId.size();
    }
}
