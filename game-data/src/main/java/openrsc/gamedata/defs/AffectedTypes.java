package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemAffectedTypes.xml}: by wearable type, the wearable types wearing it takes off.
 * An unknown type takes off none.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L198">EntityHandler.load</a>
 */
public record AffectedTypes(Map<Integer, List<Integer>> byType) {

    public static AffectedTypes from(XmlTree root) {
        Map<Integer, List<Integer>> m = new HashMap<>();
        ExtrasXml.eachEntry(root, (type, arr) -> {
            List<Integer> types = new ArrayList<>();
            for (XmlTree i : arr.children("int")) {
                types.add(Integer.parseInt(i.text()));
            }
            m.put(type, List.copyOf(types));
        });
        return new AffectedTypes(Map.copyOf(m));
    }

    public int size() {
        return byType.size();
    }
}
