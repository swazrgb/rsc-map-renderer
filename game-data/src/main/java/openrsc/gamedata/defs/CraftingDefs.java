package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemCraftingDef.xml}: jewellery by crafting index.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L206">EntityHandler.load</a>
 */
public record CraftingDefs(Map<Integer, CraftingDef> byId) {

    public static CraftingDefs from(XmlTree root) {
        Map<Integer, CraftingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id,
                        new CraftingDef(
                                d.intOf("requiredLvl", 0),
                                d.intOf("itemID", 0),
                                d.intOf("exp", 0),
                                d.intOf("gemID", -1))));
        return new CraftingDefs(Map.copyOf(m));
    }

    public int size() {
        return byId.size();
    }
}
