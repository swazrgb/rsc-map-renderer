package openrsc.gamedata.defs;

import java.nio.file.Path;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * Sceneries by id, read from the server's {@code GameObjectDef.xml} by {@link #loadXml}. Each lookup
 * answers {@code null} or false for an unknown id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L181">EntityHandler.load</a>
 */
public final class ObjectDefs {

    private final ObjectDef[] byId;

    private ObjectDefs(ObjectDef[] byId) {
        this.byId = byId;
    }

    public static ObjectDefs loadXml(Path file) {
        return fromXml(XmlTree.parse(file));
    }

    public static ObjectDefs fromXml(XmlTree root) {
        List<XmlTree> defs = root.children("GameObjectDef");
        ObjectDef[] arr = new ObjectDef[defs.size()];
        for (int id = 0; id < defs.size(); id++) {
            var d = defs.get(id);
            arr[id] = new ObjectDef(
                    id,
                    d.text("name"),
                    d.intOf("type", 0),
                    d.intOf("width", 1),
                    d.intOf("height", 1),
                    d.text("command1"),
                    d.text("command2"));
        }
        return new ObjectDefs(arr);
    }

    public ObjectDef get(int id) {
        return (id >= 0 && id < byId.length) ? byId[id] : null;
    }

    public int size() {
        return byId.length;
    }

    /**
     * Whether this is a closed gate or door any player opens for good with a click: the server swaps it
     * for its open form, which does not block, with no timer and no one moved through it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1196">DoorAction.handleGates</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L1166">DoorAction.handleObjectDoor</a>
     */
    public boolean isGenericFreeDoor(int id) {
        return id == 57 || id == 60 || id == 64;
    }
}
