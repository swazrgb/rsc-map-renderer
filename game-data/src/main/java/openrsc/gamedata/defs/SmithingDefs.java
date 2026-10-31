package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemSmithingDef.xml}: what an anvil makes, in file order; the index is the one the
 * anvil menu uses.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L205">EntityHandler.load</a>
 */
public record SmithingDefs(List<SmithingDef> defs) {

    public static SmithingDefs from(XmlTree root) {
        List<SmithingDef> out = new ArrayList<>();
        for (XmlTree d : root.children("ItemSmithingDef")) {
            out.add(new SmithingDef(
                    d.intOf("level", 0), d.intOf("bars", 0), d.intOf("itemID", 0), d.intOf("amount", 0)));
        }
        return new SmithingDefs(List.copyOf(out));
    }

    public int size() {
        return defs.size();
    }
}
