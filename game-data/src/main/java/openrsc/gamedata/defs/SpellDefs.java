package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code SpellDef.xml}: the spells in file order; a spell's id is its index. {@link #get}
 * answers {@code null} for an id out of range.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L185">EntityHandler.load</a>
 */
public record SpellDefs(List<SpellDef> defs) {

    public static SpellDefs from(XmlTree root) {
        List<SpellDef> out = new ArrayList<>();
        int id = 0;
        for (XmlTree d : root.children("SpellDef")) {
            out.add(new SpellDef(id++, d.text("name"), d.intOf("reqLevel", 0)));
        }
        return new SpellDefs(List.copyOf(out));
    }

    public SpellDef get(int spellId) {
        if (spellId < 0 || spellId >= defs.size()) {
            return null;
        }
        return defs.get(spellId);
    }

    public int size() {
        return defs.size();
    }
}
