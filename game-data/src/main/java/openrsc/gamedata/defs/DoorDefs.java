package openrsc.gamedata.defs;

import java.nio.file.Path;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * Boundaries by id, as {@link #loadXml} reads them from the server's {@code DoorDef.xml}. Each
 * lookup answers {@code null}, 0 or false for an id out of range.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L180">EntityHandler.load</a>
 */
public final class DoorDefs {

    private final DoorDef[] byId;

    private DoorDefs(DoorDef[] byId) {
        this.byId = byId;
    }

    public static DoorDefs loadXml(Path file) {
        return fromXml(XmlTree.parse(file));
    }

    public static DoorDefs fromXml(XmlTree root) {
        List<XmlTree> defs = root.children("DoorDef");
        DoorDef[] arr = new DoorDef[defs.size()];
        for (int id = 0; id < defs.size(); id++) {
            var d = defs.get(id);
            arr[id] = new DoorDef(
                    d.text("name"),
                    d.intOf("doorType", 0),
                    d.intOf("unknown", 0),
                    d.text("command1"),
                    d.text("command2"));
        }
        return new DoorDefs(arr);
    }

    public DoorDef get(int id) {
        return (id >= 0 && id < byId.length) ? byId[id] : null;
    }

    public String name(int id) {
        return (id >= 0 && id < byId.length) ? byId[id].name() : null;
    }

    public int doorType(int id) {
        return (id >= 0 && id < byId.length) ? byId[id].doorType() : 0;
    }

    /**
     * Whether a wall of this boundary baked into the landscape blocks: its {@code doorType} is not 0
     * and its {@code unknown} field is 0. A boundary placed as an object blocks when its
     * {@code doorType} is 1.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L392">WorldLoader.loadSection</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L547">World.registerGameObject</a>
     */
    public boolean collides(int id) {
        return id >= 0 && id < byId.length && byId[id].nonBlocking() == 0 && byId[id].doorType() != 0;
    }

    /** Whether this is a door: a blocking boundary with a verb other than "WalkTo", which walls carry. */
    public boolean isOpenable(int id) {
        if (id < 0 || id >= byId.length) {
            return false;
        }
        DoorDef e = byId[id];
        if (e.doorType() != 1) {
            return false;
        }
        String cmd = e.command1();
        if (cmd == null || cmd.isEmpty()) {
            return false;
        }
        return !cmd.equalsIgnoreCase("WalkTo");
    }

    /**
     * Whether this is one of the two doors any player opens for good with a click: the server swaps it
     * for its open doorframe, with no timer and no one moved through it. Every other door blocks for
     * everyone and is crossed by its own handler.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L268">DoorAction.onWallObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L460">DoorAction.onWallObjectAction</a>
     */
    public boolean isGenericFreeDoor(int id) {
        return id == 2 || id == 8;
    }
}
