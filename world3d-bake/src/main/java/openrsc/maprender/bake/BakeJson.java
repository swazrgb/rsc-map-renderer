package openrsc.maprender.bake;

import tools.jackson.databind.ObjectMapper;

final class BakeJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private BakeJson() {}

    /** A def's menu command as the viewer offers it: {@code null} when blank or the implicit WalkTo. */
    static String menuCmd(String cmd) {
        if (cmd == null || cmd.isBlank() || cmd.equalsIgnoreCase("WalkTo")) {
            return null;
        }
        return cmd;
    }
}
