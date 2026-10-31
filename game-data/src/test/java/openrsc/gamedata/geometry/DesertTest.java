package openrsc.gamedata.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;
import org.junit.jupiter.api.Test;

class DesertTest {

    private static ItemDef worn(int id, int slot, int armour) {
        return new ItemDef(
                id, "", "", 0, false, true, 0, 0, slot, 0, false, false, false, false, "", 0, armour, 0, 0, 0, 0);
    }

    @Test
    void theHeatSparesThePassTheDunesAndTheCamps() {
        assertTrue(Desert.contains(80, 754));
        assertFalse(Desert.contains(63, 725), "Shantay Pass");
        assertFalse(Desert.contains(70, 722), "the dunes of Al Kharid");
        assertFalse(Desert.contains(85, 805), "the mining camp");
        assertFalse(Desert.contains(170, 790), "the Bedabin camp");
        assertFalse(Desert.contains(80, 720));
        assertTrue(Desert.beyondThePass(85, 805), "a camp no walk reaches but through the heat");
        assertFalse(Desert.beyondThePass(63, 725));
    }

    @Test
    void armourHastensTheStrokesAndDesertClothesSlowThem() {
        assertEquals(140, Desert.strokeTicks(List.of()));
        assertEquals(
                60,
                Desert.strokeTicks(List.of(
                        worn(ItemId.LARGE_MITHRIL_HELMET.id(), 0, 9),
                        worn(ItemId.MITHRIL_PLATE_MAIL_BODY.id(), 1, 20),
                        worn(ItemId.MITHRIL_PLATE_MAIL_LEGS.id(), 2, 15))));
        assertEquals(
                190,
                Desert.strokeTicks(List.of(
                        worn(ItemId.DESERT_SHIRT.id(), 1, 0),
                        worn(ItemId.DESERT_ROBE.id(), 7, 0),
                        worn(ItemId.DESERT_BOOTS.id(), 9, 0))));
        assertEquals(100, Desert.strokeTicks(List.of(worn(ItemId.STEEL_CHAIN_MAIL_BODY.id(), 6, 18))), "a chain body");
        assertEquals(4, Desert.mouthfuls(ItemId.FULL_WATER_SKIN.id()));
        assertEquals(0, Desert.mouthfuls(ItemId.EMPTY_WATER_SKIN.id()));
    }
}
