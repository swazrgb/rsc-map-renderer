package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.BankSite;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class BanksGoldenTest {

    @Test
    void bankTilesAreExactlyTheValidatedSet() {
        List<Point> tiles = Banks.ALL.stream().map(BankSite::tile).toList();
        int[][] expected = {
            {89, 695}, // Al Kharid
            {151, 505}, // Varrock West
            {102, 513}, // Varrock East
            {332, 553}, // Falador West
            {283, 567}, // Falador East
            {219, 449}, // Edgeville
            {220, 635}, // Draynor
            {501, 450}, // Seers' Village
            {440, 492}, // Catherby
            {581, 574}, // East Ardougne North
            {552, 613}, // East Ardougne South
            {587, 754}, // Yanille
            {454, 3378}, // Mage Arena
            {173, 3524}, // Zanaris
            {714, 1453}, // Tree Gnome Stronghold
            {715, 1401}, // Grand Tree
            {402, 851}, // Shilo Village
            {514, 2423}, // Legends' Guild, upstairs
            {59, 731}, // Shantay Pass bank chest
            {604, 505}, // Fishing Guild bank chest
            {345, 609}, // Crafting Guild bank chest
            {555, 455}, // Woodcutting Guild bank chest
        };
        assertEquals(expected.length, tiles.size(), "bank tile count");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Point.of(expected[i][0], expected[i][1]), tiles.get(i), "bank tile #" + i);
        }
    }

    @Test
    void eachWorldHasTheBanksItsLocsHave() {
        List<Point> coleslawOnly =
                List.of(Point.of(332, 553), Point.of(604, 505), Point.of(345, 609), Point.of(555, 455));
        List<Point> uranium = Banks.tiles(RealDefs.serverData());
        assertEquals(
                Banks.ALL.size() - coleslawOnly.size(),
                uranium.size(),
                "Uranium: every bank but the guild chests and Falador west");
        coleslawOnly.forEach(g -> assertEquals(false, uranium.contains(g), "Uranium has no bank at " + g));
        List<Point> coleslaw = Banks.tiles(RealDefs.serverData(RealDefs.COLESLAW));
        assertEquals(Banks.ALL.size(), coleslaw.size(), "Coleslaw: every bank, the guild chests and Falador west");
        assertSame(Banks.on(RealDefs.serverData()), Banks.on(RealDefs.serverData()), "one list per world");
    }
}
