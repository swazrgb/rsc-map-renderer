package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.GameState;
import openrsc.bot.api.MutableGameState;
import openrsc.bot.api.SessionKey;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.world.WallOverlay;
import openrsc.livemap.dto.MapEntityDto;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BotLiveEncoderTombstoneTest {

    private static int gridRadius;

    private static ServerData env;
    private static BotLiveEncoder encoder;

    @BeforeAll
    static void load() throws Exception {
        env = RealDefs.serverData();
        gridRadius = env.world().liveViewGridRadius();
        encoder = new BotLiveEncoder(env);
    }

    private static SceneryLocs.Loc uniqueTileTarget() {
        Map<Long, Integer> counts = new HashMap<>();
        for (SceneryLocs.Loc l : env.sceneryLocs()) {
            counts.merge(key(l.pos().x(), l.pos().y()), 1, Integer::sum);
        }
        for (SceneryLocs.Loc l : env.sceneryLocs()) {
            if (counts.get(key(l.pos().x(), l.pos().y())) == 1) {
                return l;
            }
        }
        throw new IllegalStateException("no tile-unique static scenery in the game data?");
    }

    private static long key(int x, int z) {
        return ((long) x << 20) | (z & 0xfffff);
    }

    private static MutableGameState fullMirror(int bx, int bz) {
        MutableGameState world = new MutableGameState(
                env,
                SessionKey.ofAccount("offline"),
                new WallOverlay(),
                RealDefs.pathfinder().nav());
        world.setSelfCoords(bx, bz, 0);
        for (SceneryLocs.Loc l : env.sceneryLocs()) {
            if (WorldProfile.absenceIsConclusive(l.pos().x(), l.pos().y(), bx, bz, gridRadius)) {
                world.updateObject(l.id(), l.pos().x(), l.pos().y(), l.direction());
            }
        }
        for (BoundaryLocs.Loc l : env.boundaryLocs()) {
            if (WorldProfile.absenceIsConclusive(l.pos().x(), l.pos().y(), bx, bz, gridRadius)) {
                world.updateWallObject(l.id(), l.pos().x(), l.pos().y(), l.direction());
            }
        }
        return world;
    }

    private static List<MapEntityDto> tombstones(List<MapEntityDto> rows) {
        List<MapEntityDto> out = new ArrayList<>();
        for (MapEntityDto e : rows) {
            if (e.id() < 0) {
                out.add(e);
            }
        }
        return out;
    }

    @Test
    void missingStaticObjectShipsExactlyOneTombstone() {
        SceneryLocs.Loc target = uniqueTileTarget();
        int bx = target.pos().x();
        int bz = target.pos().y();

        MutableGameState world = fullMirror(bx, bz);
        world.updateObject(60000, bx, bz, 0);

        GameState frozen = world.freezeTick();
        List<MapEntityDto> objects = encoder.objects(frozen);
        List<MapEntityDto> walls = encoder.walls(frozen);
        List<MapEntityDto> ghosts = tombstones(objects);
        assertEquals(1, ghosts.size(), "exactly the removed static object is tombstoned");
        assertEquals(bx, ghosts.get(0).x());
        assertEquals(bz, ghosts.get(0).z());
        assertEquals(-1, ghosts.get(0).id());
        assertEquals(1, objects.size(), "no deviation rows for matching statics");
        assertTrue(tombstones(walls).isEmpty(), "all boundaries present -> none tombstoned");
    }

    @Test
    void fullyMirroredWorldShipsNoTombstones() {
        SceneryLocs.Loc target = uniqueTileTarget();
        MutableGameState world = fullMirror(target.pos().x(), target.pos().y());
        GameState frozen = world.freezeTick();
        List<MapEntityDto> objects = encoder.objects(frozen);
        List<MapEntityDto> walls = encoder.walls(frozen);
        assertTrue(tombstones(objects).isEmpty());
        assertTrue(tombstones(walls).isEmpty());
        assertTrue(objects.isEmpty(), "static matches are filtered, nothing deviates");
        assertTrue(walls.isEmpty());
    }

    @Test
    void missingStaticBoundaryShipsWallTombstoneWithItsDirection() {
        Map<Long, Integer> counts = new HashMap<>();
        for (BoundaryLocs.Loc l : env.boundaryLocs()) {
            long k = ((long) (l.direction() & 7) << 44)
                    | key(l.pos().x(), l.pos().y());
            counts.merge(k, 1, Integer::sum);
        }
        BoundaryLocs.Loc target = null;
        outer:
        for (BoundaryLocs.Loc l : env.boundaryLocs()) {
            long k = ((long) (l.direction() & 7) << 44)
                    | key(l.pos().x(), l.pos().y());
            if (counts.get(k) != 1) {
                continue;
            }
            for (SceneryLocs.Loc s : env.sceneryLocs()) {
                if (WorldProfile.absenceIsConclusive(
                        s.pos().x(), s.pos().y(), l.pos().x(), l.pos().y(), gridRadius)) {
                    target = l;
                    break outer;
                }
            }
        }
        int bx = target.pos().x();
        int bz = target.pos().y();

        MutableGameState world = fullMirror(bx, bz);
        world.updateWallObject(60000, bx, bz, target.direction());

        GameState frozen = world.freezeTick();
        List<MapEntityDto> objects = encoder.objects(frozen);
        List<MapEntityDto> walls = encoder.walls(frozen);
        List<MapEntityDto> ghosts = tombstones(walls);
        assertEquals(1, ghosts.size());
        assertEquals(bx, ghosts.get(0).x());
        assertEquals(bz, ghosts.get(0).z());
        assertEquals(target.direction(), ghosts.get(0).dir());
        assertTrue(tombstones(objects).isEmpty(), "scenery untouched -> no object tombstones");
    }
}
