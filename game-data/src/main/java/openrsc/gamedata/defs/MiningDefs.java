package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ObjectMining.xml}: rocks by scenery id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L207">EntityHandler.load</a>
 */
public record MiningDefs(Map<Integer, MiningDef> bySceneryId) {

    public static MiningDefs from(XmlTree root) {
        Map<Integer, MiningDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id,
                        new MiningDef(
                                d.intOf("requiredLvl", 0),
                                d.intOf("oreId", 0),
                                d.intOf("exp", 0),
                                d.intOf("depletion", 0),
                                d.intOf("respawnTime", 0))));
        return new MiningDefs(Map.copyOf(m));
    }

    public int size() {
        return bySceneryId.size();
    }
}
