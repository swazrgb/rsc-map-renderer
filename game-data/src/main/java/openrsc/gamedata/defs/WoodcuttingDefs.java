package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ObjectWoodcutting.xml}: trees by scenery id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L208">EntityHandler.load</a>
 */
public record WoodcuttingDefs(Map<Integer, WoodcuttingDef> bySceneryId) {

    /** The axes, in the order of {@link WoodcuttingDef#low()} and {@link WoodcuttingDef#high()}. */
    @SuppressWarnings("MutablePublicArray")
    public static final String[] AXE_TIERS = {
        "bronze", "iron", "steel", "black", "mithril", "adamantite", "rune", "dragon"
    };

    public static WoodcuttingDefs from(XmlTree root) {
        Map<Integer, WoodcuttingDef> m = new HashMap<>();
        ExtrasXml.eachEntry(root, (id, d) -> {
            int[] low = new int[AXE_TIERS.length];
            int[] high = new int[AXE_TIERS.length];
            for (int i = 0; i < AXE_TIERS.length; i++) {
                String tier = Character.toUpperCase(AXE_TIERS[i].charAt(0)) + AXE_TIERS[i].substring(1);
                low[i] = d.intOf("low" + tier, 0);
                high[i] = d.intOf("high" + tier, 0);
            }
            m.put(
                    id,
                    new WoodcuttingDef(
                            d.intOf("requiredLevel", 0),
                            d.intOf("logId", 0),
                            d.intOf("exp", 0),
                            d.intOf("fell", 0),
                            d.intOf("respawnTime", 0),
                            low,
                            high));
        });
        return new WoodcuttingDefs(Map.copyOf(m));
    }

    public int size() {
        return bySceneryId.size();
    }
}
