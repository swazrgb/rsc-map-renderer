package openrsc.gamedata.defs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * The server's {@code ObjectTelePoints.xml}: ladders and stairs whose destination is given per tile
 * and verb, rather than one floor up or down.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L968">EntityHandler.getObjectTelePoint</a>
 */
public final class ObjectTelePoints {

    /** Using {@code command} on the scenery at {@code (x, y)} moves the player to {@code (destX, destY)}. */
    public record Point(int x, int y, String command, int destX, int destY) {}

    private ObjectTelePoints() {}

    public static List<Point> load(Path file) {
        return from(XmlTree.parse(file));
    }

    /** The entries of a parsed file; one without a verb is skipped. */
    public static List<Point> from(XmlTree root) {
        List<Point> out = new ArrayList<>();
        for (XmlTree entry : root.children("entry")) {
            XmlTree from = entry.child("Point");
            XmlTree to = entry.child("TelePoint");
            String command = to == null ? null : to.text("command");
            if (command == null) {
                command = entry.text("command");
            }
            if (command == null || from == null || to == null) {
                continue;
            }
            out.add(new Point(from.intOf("x", 0), from.intOf("y", 0), command, to.intOf("x", 0), to.intOf("y", 0)));
        }
        return List.copyOf(out);
    }
}
