package openrsc.map.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.transports.EngageSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MapGeoJsonExporterDoorCoverageTest {

    private static DoorDefs doorDefs;

    @BeforeAll
    static void loadDoorDefs() {
        doorDefs = RealDefs.serverData().doorDefs();
    }

    private static Transport transport(
            List<Point> entries, int destX, int destY, EngageSpec.Target.Boundary boundary, Point sceneryAnchor) {
        return new Transport() {
            @Override
            public boolean isEntry(int x, int y) {
                return entries.stream().anyMatch(e -> e.x() == x && e.y() == y);
            }

            @Override
            public List<Point> entries() {
                return entries;
            }

            @Override
            public Point destinationFrom(Point from) {
                return Point.of(destX, destY);
            }

            @Override
            public EngageSpec.Target.Boundary boundary() {
                return boundary;
            }

            @Override
            public Point sceneryAnchor() {
                return sceneryAnchor;
            }

            @Override
            public int cost() {
                return 10;
            }

            @Override
            public String name() {
                return "test transport";
            }
        };
    }

    private static Transport ladder(int x, int y) {
        List<Point> entries = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                entries.add(Point.of(x + dx, y + dy));
            }
        }
        return transport(entries, x, y + 944, null, Point.of(x, y));
    }

    private static BoundaryLocs.Loc door(int x, int y, int dir) {
        return new BoundaryLocs.Loc(2, new BoundaryLocs.Pos(x, y), dir);
    }

    private static List<Point> renderedDoorStarts(JsonNode fc) {
        List<Point> out = new ArrayList<>();
        for (JsonNode feat : fc.get("features")) {
            if (!"door".equals(feat.get("properties").get("kind").asString())) {
                continue;
            }
            JsonNode p0 = feat.get("geometry").get("coordinates").get(0);
            out.add(Point.of(p0.get(0).asInt(), p0.get(1).asInt()));
        }
        return out;
    }

    @Test
    void free_diagonal_doors_render_despite_a_ladder_transport_nearby() {
        List<BoundaryLocs.Loc> doors =
                List.of(door(360, 568, 2), door(364, 569, 3), door(359, 572, 3), door(363, 573, 2));

        JsonNode fc = MapGeoJsonExporter.buildFloor(
                0, doors, doorDefs, List.of(), null, List.of(ladder(360, 570)), List.of(), null, null);

        assertEquals(
                4,
                renderedDoorStarts(fc).size(),
                "a ladder near (but not anchored on) diagonal doors must not suppress them");
    }

    @Test
    void door_with_an_anchored_transport_is_suppressed() {
        List<BoundaryLocs.Loc> doors = List.of(door(100, 100, 2), door(105, 100, 3));
        Transport crosser = transport(List.of(Point.of(100, 99)), 100, 101, wall(100, 100, 2), null);

        JsonNode fc = MapGeoJsonExporter.buildFloor(
                0, doors, doorDefs, List.of(), null, List.of(crosser), List.of(), null, null);

        List<Point> rendered = renderedDoorStarts(fc);
        assertEquals(1, rendered.size(), "anchored door suppressed, free door kept");
        assertEquals(106, rendered.get(0).x());
        assertEquals(101, rendered.get(0).y());
    }

    @Test
    void anchors_do_not_leak_across_kinds_or_tiles() {
        List<BoundaryLocs.Loc> doors = List.of(door(50, 50, 0), door(60, 50, 0));
        Transport flankEntryNoAnchor = transport(List.of(Point.of(50, 50)), 500, 3000, null, null);
        Transport sceneryAnchoredSameTile = transport(List.of(Point.of(60, 49)), 60, 55, null, Point.of(60, 50));
        Transport claims60 = transport(List.of(Point.of(60, 49)), 60, 50, wall(60, 50, 0), null);

        JsonNode fc = MapGeoJsonExporter.buildFloor(
                0,
                doors,
                doorDefs,
                List.of(),
                null,
                List.of(flankEntryNoAnchor, sceneryAnchoredSameTile, claims60),
                List.of(),
                null,
                null);

        List<Point> rendered = renderedDoorStarts(fc);
        assertEquals(
                1,
                rendered.size(),
                "only the boundary-anchored transport suppresses its door; entry-tile overlap and "
                        + "scenery anchors on the same tile do not");
        assertTrue(rendered.get(0).x() == 50, "the unanchored (50,50) door is the one that renders");
    }

    @Test
    void a_boundary_is_claimed_by_its_edge_not_its_tile() {
        List<BoundaryLocs.Loc> doors = List.of(door(70, 50, 0), door(70, 50, 1));
        Transport eastEdge = transport(List.of(Point.of(71, 50)), 69, 50, wall(70, 50, 1), null);

        JsonNode fc = MapGeoJsonExporter.buildFloor(
                0, doors, doorDefs, List.of(), null, List.of(eastEdge), List.of(), null, null);
        List<Point> rendered = renderedDoorStarts(fc);
        assertEquals(1, rendered.size(), "only the crossed edge is suppressed");
        assertEquals(Point.of(70, 50), rendered.get(0));

        Transport anyEdge = transport(List.of(Point.of(71, 50)), 69, 50, wall(70, 50, -1), null);
        JsonNode unknown = MapGeoJsonExporter.buildFloor(
                0, doors, doorDefs, List.of(), null, List.of(anyEdge), List.of(), null, null);
        assertEquals(
                0,
                renderedDoorStarts(unknown).size(),
                "an edge the definition does not know claims every wall of the tile");
    }

    private static EngageSpec.Target.Boundary wall(int x, int y, int dir) {
        return new EngageSpec.Target.Boundary(2, x, y, dir);
    }
}
