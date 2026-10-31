package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemHerbSecond.xml}: second ingredients, in file order.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L191">EntityHandler.load</a>
 */
public record HerbSeconds(List<HerbSecondDef> defs) {

    public static HerbSeconds from(XmlTree root) {
        List<HerbSecondDef> out = new ArrayList<>();
        for (XmlTree d : root.children("ItemHerbSecond")) {
            out.add(new HerbSecondDef(
                    d.intOf("secondID", 0),
                    d.intOf("unfinishedID", 0),
                    d.intOf("potionID", 0),
                    d.intOf("requiredLvl", 0),
                    d.intOf("exp", 0)));
        }
        return new HerbSeconds(List.copyOf(out));
    }

    public int size() {
        return defs.size();
    }
}
