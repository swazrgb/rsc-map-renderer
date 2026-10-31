package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemSmeltingDef.xml}: bars by their first ore's item id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L204">EntityHandler.load</a>
 */
public record SmeltingDefs(Map<Integer, SmeltingDef> byOreId) {

    public static SmeltingDefs from(XmlTree root) {
        Map<Integer, SmeltingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(root, (id, d) -> {
            List<ReqOre> reqOres = new ArrayList<>();
            XmlTree reqs = d.child("reqOres");
            if (reqs != null) {
                for (XmlTree r : reqs.children("ReqOreDef")) {
                    reqOres.add(new ReqOre(r.intOf("oreId", 0), r.intOf("amount", 0)));
                }
            }
            m.put(
                    id,
                    new SmeltingDef(
                            d.intOf("requiredLvl", 0), d.intOf("barId", 0), d.intOf("exp", 0), List.copyOf(reqOres)));
        });
        return new SmeltingDefs(Map.copyOf(m));
    }

    public int size() {
        return byOreId.size();
    }
}
