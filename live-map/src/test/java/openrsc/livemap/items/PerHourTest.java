package openrsc.livemap.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PerHourTest {

    @Test
    void aRateKeepsTheDigitsTheViewerShows() {
        assertEquals(12_000_000, PerHour.shown(12_345_678.9));
        assertEquals(1_700_000, PerHour.shown(1_743_916.2));
        assertEquals(-8_900_000, PerHour.shown(-8_890_985.9));
        assertEquals(123_000, PerHour.shown(123_456.7));
        assertEquals(1_200, PerHour.shown(1_234.5));
        assertEquals(17, PerHour.shown(16.6));
    }

    @Test
    void aDecayingRateStaysPutUntilItsShownDigitsMove() {
        assertEquals(
                new ItemHolderDto("a", 1, 0, false, 5_844_887.0), new ItemHolderDto("a", 1, 0, false, 5_832_603.5));
    }
}
