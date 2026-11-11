package openrsc.livemap.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import openrsc.gamedata.world.CollisionMap;
import openrsc.map.render.TerrainRenderer;
import openrsc.nav.RealDefs;
import openrsc.nav.probe.NavDangerExport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MapControllerDangerTest {

    private static MapController ctrl;

    @BeforeAll
    static void load() {
        ctrl = new MapController(MapNav.of(RealDefs.pathfinder()));
    }

    @Test
    void dangerTilesMatchTheExportAndCarrySaneValues() {
        var resp = ctrl.floorDangerTiles(0);
        assertEquals(200, resp.getStatusCode().value());
        NavDangerExport.FloorTiles tiles = resp.getBody();
        assertNotNull(tiles);
        assertEquals(0, tiles.floor());
        NavDangerExport export = NavDangerExport.build(RealDefs.pathfinder(), 0);
        assertEquals(export.rawCeiling(), tiles.ceiling());
        assertTrue(tiles.x().length > 0, "floor 0 should have dangerous tiles (wilderness et al.)");
        assertEquals(tiles.x().length, tiles.z().length);
        assertEquals(tiles.x().length, tiles.v().length);

        for (int i = 0; i < tiles.x().length; i += Math.max(1, tiles.x().length / 500)) {
            int v = tiles.v()[i];
            assertTrue(v > 0 && v <= tiles.ceiling(), "value out of range: " + v);
            assertEquals(
                    export.rawPenaltyAt(tiles.x()[i], tiles.z()[i]),
                    v,
                    "JSON value must equal the export's raw penalty at (" + tiles.x()[i] + ", " + tiles.z()[i] + ")");
        }
    }

    @Test
    void dangerPngPaintsExactlyTheDangerousTiles() throws Exception {
        var resp = ctrl.floorDangerPng(0);
        assertEquals(200, resp.getStatusCode().value());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.getBody()));
        assertEquals(CollisionMap.WIDTH * TerrainRenderer.SCALE, img.getWidth());
        assertEquals(CollisionMap.FLOOR_HEIGHT * TerrainRenderer.SCALE, img.getHeight());

        NavDangerExport.FloorTiles tiles = ctrl.floorDangerTiles(0).getBody();
        int checked = 0;
        for (int i = 0; i < tiles.x().length; i += Math.max(1, tiles.x().length / 200)) {
            int px = (CollisionMap.WIDTH - 1 - tiles.x()[i]) * TerrainRenderer.SCALE;
            int py = tiles.z()[i] * TerrainRenderer.SCALE;
            int argb = img.getRGB(px, py);
            assertTrue((argb >>> 24) > 0, "dangerous tile must be painted at (" + px + "," + py + ")");
            assertEquals(0xFF, (argb >> 16) & 0xFF, "gradient is red-anchored");
            checked++;
        }
        assertTrue(checked > 0);
        int seaPx = (CollisionMap.WIDTH - 1 - 5) * TerrainRenderer.SCALE;
        assertEquals(0, img.getRGB(seaPx, 900 * TerrainRenderer.SCALE) >>> 24, "safe tile must stay transparent");
    }

    @Test
    void aHigherCombatLevelSeesFewerHostileZones() {
        NavDangerExport.FloorTiles low =
                NavDangerExport.build(RealDefs.pathfinder(), 0).tiles(0);
        NavDangerExport.FloorTiles high =
                NavDangerExport.build(RealDefs.pathfinder(), 126).tiles(0);
        assertTrue(high.x().length > 0, "wilderness aggression ignores the level gate");
        assertTrue(
                high.x().length < low.x().length,
                "a maxed observer must see fewer hostile zones than a level-0 one (" + high.x().length + " vs "
                        + low.x().length + ")");
    }

    @Test
    void rejectsOutOfRangeFloors() {
        assertEquals(404, ctrl.floorDangerPng(-1).getStatusCode().value());
        assertEquals(
                404,
                ctrl.floorDangerTiles(CollisionMap.FLOOR_COUNT).getStatusCode().value());
    }
}
