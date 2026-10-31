package openrsc.gamedata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcDefs;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.defs.SceneryLocs;

/**
 * The game data as plain records for a JSON API or a static export. Every z here is the tile's
 * z within its floor where a {@code floor} is given, and the server's folded z (floor times 944
 * plus z) otherwise.
 */
public final class DataViews {

    /** A static NPC spawn and its roam box; {@code name} is {@code null} when no defs were given. */
    public record SpawnDto(
            int serverIndex, int id, String name, int x, int z, int minX, int minZ, int maxX, int maxZ) {}

    /** {@code respawnSeconds} is 0 for an NPC that never respawns; see {@link #npcDefs}. */
    public record NpcDefDto(
            int id,
            String name,
            String description,
            int combatLevel,
            int hits,
            int attack,
            int strength,
            int defense,
            int maxHit,
            boolean attackable,
            boolean aggressive,
            boolean ranged,
            boolean members,
            int respawnSeconds) {}

    public record SceneryDto(int id, int dir, int x, int z, int floor) {}

    public record BoundaryDto(int id, int x, int z, int floor, int dir) {}

    public record BoundariesDto(List<BoundaryDto> boundaries) {}

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L531
    private static final int FLOOR_HEIGHT = 944;

    /** Each spawn's {@code serverIndex} is its position in {@code locs}; {@code defs} may be {@code null}. */
    public static List<SpawnDto> spawns(List<NpcLocs.Spawn> locs, NpcDefs defs) {
        List<SpawnDto> out = new ArrayList<>(locs.size());
        for (int i = 0; i < locs.size(); i++) {
            NpcLocs.Spawn s = locs.get(i);
            out.add(new SpawnDto(
                    i,
                    s.id(),
                    defs == null ? null : defs.nameOf(s.id()),
                    s.start().x(),
                    s.start().z(),
                    s.min().x(),
                    s.min().z(),
                    s.max().x(),
                    s.max().z()));
        }
        return out;
    }

    /**
     * Every NPC def, by id; an id with no def is left out. A def's respawn time in seconds is scaled
     * by {@code respawnScale} (see {@link #npcRespawnScale}) and rounded, but never to 0 for an NPC
     * that respawns.
     */
    public static List<NpcDefDto> npcDefs(NpcDefs defs, double respawnScale) {
        List<NpcDefDto> out = new ArrayList<>();
        for (int id = 0; id < defs.size(); id++) {
            NpcDef d = defs.get(id);
            if (d == null) {
                continue;
            }
            out.add(new NpcDefDto(
                    d.id(),
                    d.name(),
                    d.description(),
                    d.combatLevel(),
                    d.hits(),
                    d.attack(),
                    d.strength(),
                    d.defense(),
                    d.maxHit(),
                    d.attackable(),
                    defs.isAggressive(id),
                    d.ranged(),
                    d.members(),
                    respawnSeconds(d.respawnTime(), respawnScale)));
        }
        return out;
    }

    static int respawnSeconds(int respawnTime, double respawnScale) {
        return respawnTime <= 0 ? 0 : Math.max(1, (int) Math.round(respawnTime * respawnScale));
    }

    /**
     * The factor {@link #npcDefs} scales respawn times by: the conf's {@code npc_respawn_multiplier},
     * 1.0 when absent and for a world with no {@code .conf}. The server waits the def's seconds
     * times this whatever its {@code game_tick}, rounded up to whole ticks.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/Npc.java#L840">Npc.remove</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/DelayedEvent.java#L16">DelayedEvent</a>
     */
    public static double npcRespawnScale(ServerConf conf, WorldProfile world) throws IOException {
        Path file = conf.serverDir().resolve(world.name() + ".conf");
        if (!Files.exists(file)) {
            return 1.0;
        }
        return ServerProperties.load(file)
                .string("npc_respawn_multiplier")
                .map(Double::parseDouble)
                .orElse(1.0);
    }

    public static List<SceneryDto> scenery(List<SceneryLocs.Loc> locs) {
        List<SceneryDto> out = new ArrayList<>(locs.size());
        for (SceneryLocs.Loc loc : locs) {
            int y = loc.pos().y();
            out.add(new SceneryDto(loc.id(), loc.direction(), loc.pos().x(), y % FLOOR_HEIGHT, y / FLOOR_HEIGHT));
        }
        return out;
    }

    public static BoundariesDto boundaries(List<BoundaryLocs.Loc> locs) {
        List<BoundaryDto> out = new ArrayList<>(locs.size());
        for (BoundaryLocs.Loc b : locs) {
            int y = b.pos().y();
            out.add(new BoundaryDto(b.id(), b.pos().x(), y % FLOOR_HEIGHT, y / FLOOR_HEIGHT, b.direction()));
        }
        return new BoundariesDto(out);
    }

    /**
     * The names of the wearable items drawn with each appearance sprite, by sprite id, in item id
     * order; empty for {@code null} defs.
     */
    public static Map<Integer, List<String>> wearables(ItemDefs defs) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        if (defs == null) {
            return out;
        }
        for (int id = 0; id < defs.size(); id++) {
            ItemDef e = defs.get(id);
            if (e == null || !e.wearable() || e.appearanceId() <= 0) {
                continue;
            }
            List<String> names = out.computeIfAbsent(e.appearanceId(), k -> new ArrayList<>());
            if (!names.contains(e.name())) {
                names.add(e.name());
            }
        }
        return out;
    }

    private DataViews() {}
}
