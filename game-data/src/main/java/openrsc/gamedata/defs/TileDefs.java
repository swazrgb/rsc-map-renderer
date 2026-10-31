package openrsc.gamedata.defs;

import java.nio.file.Path;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * Ground overlays by id, read from the server's {@code TileDef.xml} by {@link #loadXml}. Each lookup
 * answers 0 for an id out of range.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L188">EntityHandler.load</a>
 */
public final class TileDefs {

    private final TileDef[] byId;

    private TileDefs(TileDef[] byId) {
        this.byId = byId;
    }

    public static TileDefs loadXml(Path file) {
        return fromXml(XmlTree.parse(file));
    }

    public static TileDefs fromXml(XmlTree root) {
        List<XmlTree> defs = root.children("TileDef");
        TileDef[] arr = new TileDef[defs.size()];
        for (int id = 0; id < defs.size(); id++) {
            var d = defs.get(id);
            arr[id] = new TileDef(d.intOf("objectType", 0), d.intOf("colour", 0), d.intOf("unknown", 0));
        }
        return new TileDefs(arr);
    }

    public int objectType(int id) {
        return (id >= 0 && id < byId.length) ? byId[id].objectType() : 0;
    }

    public int colour(int id) {
        return (id >= 0 && id < byId.length) ? byId[id].colour() : 0;
    }

    public int tileValue(int id) {
        return (id >= 0 && id < byId.length) ? byId[id].tileValue() : 0;
    }
}
