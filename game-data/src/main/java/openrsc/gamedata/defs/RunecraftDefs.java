package openrsc.gamedata.defs;

import java.util.HashMap;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ObjectRunecraft.xml}: runecrafting altars by scenery id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L209">EntityHandler.load</a>
 */
public record RunecraftDefs(Map<Integer, RunecraftDef> byAltarId) {

    public static RunecraftDefs from(XmlTree root) {
        Map<Integer, RunecraftDef> m = new HashMap<>();
        ExtrasXml.eachEntry(
                root,
                (id, d) -> m.put(
                        id,
                        new RunecraftDef(
                                d.intOf("requiredLvl", 0),
                                d.intOf("runeId", 0),
                                d.text("runeName"),
                                d.intOf("exp", 0))));
        return new RunecraftDefs(Map.copyOf(m));
    }

    public int size() {
        return byAltarId.size();
    }
}
