package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ObjectHarvesting.xml}: harvestable scenery by id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L211">EntityHandler.load</a>
 */
public record HarvestingDefs(Map<Integer, HarvestingDef> bySceneryId) {

    public static HarvestingDefs from(XmlTree root) {
        Map<Integer, HarvestingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id,
                        new HarvestingDef(
                                d.intOf("requiredLvl", 0),
                                d.intOf("prodId", 0),
                                d.intOf("exp", 0),
                                d.intOf("exhaust", 0),
                                d.intOf("respawnTime", 0))));
        return new HarvestingDefs(Map.copyOf(m));
    }

    public int size() {
        return bySceneryId.size();
    }
}
