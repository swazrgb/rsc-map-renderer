package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code FiremakingDef.xml}: burnable logs by item id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L197">EntityHandler.load</a>
 */
public record FiremakingDefs(Map<Integer, FiremakingDef> byLogId) {

    public static FiremakingDefs from(XmlTree root) {
        Map<Integer, FiremakingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(id, new FiremakingDef(d.intOf("level", 0), d.intOf("exp", 0), d.intOf("length", 0))));
        return new FiremakingDefs(Map.copyOf(m));
    }

    public int size() {
        return byLogId.size();
    }
}
