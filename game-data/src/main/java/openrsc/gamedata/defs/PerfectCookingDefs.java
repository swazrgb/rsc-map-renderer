package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemPerfectCookingDef.xml}: by raw item id, the Cooking level from which the food no
 * longer burns. A food without an entry stops burning 35 levels above its required level.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L203">EntityHandler.load</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L181">Formulae.burnFood</a>
 */
public record PerfectCookingDefs(Map<Integer, PerfectCookingDef> byItemId) {

    public static PerfectCookingDefs from(XmlTree root) {
        Map<Integer, PerfectCookingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root, (id, d) -> m.put(id, new PerfectCookingDef(d.intOf("requiredLvl", 0), d.intOf("exp", 0))));
        return new PerfectCookingDefs(Map.copyOf(m));
    }

    public int size() {
        return byItemId.size();
    }
}
