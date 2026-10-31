package openrsc.gamedata.defs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NPCs by id: the server's {@code NpcDefs.json}, then {@code NpcDefsCustom.json}. Ids without a
 * def answer {@code null} from {@link #get} and {@link #nameOf}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L162">EntityHandler.load</a>
 */
public final class NpcDefs {

    private final boolean[] aggressive;
    private final NpcDef[] byId;

    private NpcDefs(boolean[] aggressive, NpcDef[] byId) {
        this.aggressive = aggressive;
        this.byId = byId;
    }

    /** Defs indexed by id, {@code null} where there is none; the array is used as is, not copied. */
    public static NpcDefs of(NpcDef[] byId) {
        boolean[] agg = new boolean[byId.length];
        for (int id = 0; id < byId.length; id++) {
            NpcDef d = byId[id];
            agg[id] = d != null && d.attackable() && d.aggressive();
        }
        return new NpcDefs(agg, byId);
    }

    /**
     * Reads {@code baseFile}, then {@code customFile} when it exists. A missing base file logs a
     * warning and gives an empty table, in which every NPC is {@link #isAggressive aggressive}.
     *
     * @throws IOException when a file cannot be read or lacks its array of NPCs
     */
    public static NpcDefs load(Path baseFile, Path customFile) throws IOException {
        if (baseFile == null || !Files.exists(baseFile)) {
            LoggerFactory.getLogger(NpcDefs.class)
                    .warn(
                            "NpcDefs.json missing at {} — treating EVERY npc as blocking "
                                    + "(over-detours); copy NpcDefs.json + NpcDefsCustom.json from "
                                    + "the server's conf/server/defs/ into data/ to fix",
                            baseFile);
            return new NpcDefs(new boolean[0], new NpcDef[0]);
        }
        ObjectMapper mapper = new ObjectMapper();
        List<NpcDef> entries = new ArrayList<>();
        readInto(mapper, baseFile, entries);
        if (customFile != null && Files.exists(customFile)) {
            readInto(mapper, customFile, entries);
        }
        int maxId = 0;
        for (NpcDef d : entries) {
            maxId = Math.max(maxId, d.id());
        }
        boolean[] agg = new boolean[maxId + 1];
        NpcDef[] byId = new NpcDef[maxId + 1];
        for (NpcDef d : entries) {
            agg[d.id()] = d.attackable() && d.aggressive();
            byId[d.id()] = d;
        }
        return new NpcDefs(agg, byId);
    }

    /** One more than the highest id. */
    public int size() {
        return aggressive.length;
    }

    public NpcDef get(int id) {
        return (id >= 0 && id < byId.length) ? byId[id] : null;
    }

    public String nameOf(int id) {
        NpcDef d = get(id);
        return d != null ? d.name() : null;
    }

    /**
     * The server's test: attackable and flagged aggressive. An id without a def answers true, the
     * cautious answer.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/NPCDef.java#L191">NPCDef.isAggressive</a>
     */
    public boolean isAggressive(int id) {
        if (id < 0 || id >= aggressive.length) {
            return true;
        }
        return aggressive[id];
    }

    private static void readInto(ObjectMapper mapper, Path file, List<NpcDef> entries) throws IOException {
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        JsonNode npcs = root.path("npcs");
        if (!npcs.isArray()) {
            throw new IOException("no \"npcs\" array in " + file);
        }
        for (JsonNode n : npcs) {
            entries.add(new NpcDef(
                    n.path("id").asInt(-1),
                    n.path("name").asString(null),
                    n.path("combatlvl").asInt(0),
                    n.path("hits").asInt(0),
                    n.path("attackable").asInt(0) != 0,
                    n.path("aggressive").asInt(0) != 0,
                    n.path("respawnTime").asInt(0),
                    n.path("description").asString(null),
                    n.path("attack").asInt(0),
                    n.path("strength").asInt(0),
                    n.path("defense").asInt(0),
                    n.path("ranged").asBoolean(false),
                    n.path("isMembers").asInt(0) != 0));
        }
    }
}
