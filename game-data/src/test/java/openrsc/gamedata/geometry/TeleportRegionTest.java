package openrsc.gamedata.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TeleportRegionTest {

    // A column inside the wilderness, where on floor 0 the level is 1 + (427 - z) / 6.
    private static final int X = 215;

    @Test
    void depthAnchorsMatchTheServerFormula() {
        assertEquals(0, Wilderness.level(X, 500));
        assertEquals(19, Wilderness.level(X, 314));
        assertEquals(20, Wilderness.level(X, 313));
        assertEquals(29, Wilderness.level(X, 254));
        assertEquals(30, Wilderness.level(X, 248));
    }

    @Test
    void spellsStopAtDepth20() {
        assertTrue(TeleportRegion.SPELLS.contains(X, 500), "outside the wilderness");
        assertTrue(TeleportRegion.SPELLS.contains(X, 314), "depth 19");
        assertFalse(TeleportRegion.SPELLS.contains(X, 313), "depth 20: 'You can't use teleport after level 20'");
    }

    @Test
    void itemsStopAtDepth30() {
        assertTrue(TeleportRegion.ITEMS.contains(X, 313), "depth 20 is fine for an amulet");
        assertTrue(TeleportRegion.ITEMS.contains(X, 254), "depth 29");
        assertFalse(TeleportRegion.ITEMS.contains(X, 248), "depth 30: GLORY_TELEPORT_LIMIT");
    }

    @Test
    void grandTreeGroundFloorIsBlockedEdgeToEdge() {
        for (TeleportRegion r : new TeleportRegion[] {TeleportRegion.SPELLS, TeleportRegion.ITEMS}) {
            assertFalse(r.contains(410, 158));
            assertFalse(r.contains(422, 170));
            assertTrue(r.contains(409, 158), "one west of the box");
            assertTrue(r.contains(423, 170), "one east of the box");
            assertTrue(r.contains(416, 158 + 944), "the tree's first floor is outside the box");
        }
    }

    @Test
    void fisherKingRealmIsBlockedOnEveryFloor() {
        assertFalse(TeleportRegion.ITEMS.contains(388, 4));
        assertFalse(TeleportRegion.ITEMS.contains(523, 40));
        assertFalse(TeleportRegion.ITEMS.contains(414, 11), "where killing the Titan lands the player");
        assertFalse(TeleportRegion.ITEMS.contains(415, 980));
        assertFalse(TeleportRegion.ITEMS.contains(518, 1925));
        assertTrue(TeleportRegion.ITEMS.contains(450, 20), "between the two realm halves");
        assertTrue(TeleportRegion.ITEMS.contains(415, 41), "one south of the realm");
    }

    @Test
    void modRoomIsBlocked() {
        assertFalse(TeleportRegion.SPELLS.contains(72, 1641));
        assertTrue(TeleportRegion.SPELLS.contains(72, 1644));
    }

    @Test
    void regionsAreValues() {
        assertEquals(TeleportRegion.SPELLS, new TeleportRegion(20), "one cast source per region: equal by value");
    }
}
