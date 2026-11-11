package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import openrsc.bot.api.GroundItem;
import openrsc.livemap.dto.MapEntityDto;
import org.junit.jupiter.api.Test;

class BotLiveEncoderGroundTest {

    @Test
    void everyItemOfAPileGetsAKeyOfItsOwn() {
        List<GroundItem> pile = List.of(
                new GroundItem(20, 210, 3400),
                new GroundItem(373, 210, 3400),
                new GroundItem(373, 210, 3400),
                new GroundItem(28, 210, 3400),
                new GroundItem(20, 211, 3400));

        List<MapEntityDto> dtos = BotLiveEncoder.groundItems(pile, null);

        assertEquals(5, dtos.size());
        assertEquals(5, dtos.stream().map(MapEntityDto::serverIndex).distinct().count());
    }

    @Test
    void twoObserversKeyOnePileAlike() {
        GroundItem bones = new GroundItem(20, 210, 3400);
        GroundItem lobster = new GroundItem(373, 210, 3400);
        GroundItem arrows = new GroundItem(11, 210, 3400);

        assertEquals(
                idsByKey(List.of(bones, lobster, arrows, lobster)), idsByKey(List.of(lobster, arrows, lobster, bones)));
    }

    private static Map<Integer, Integer> idsByKey(List<GroundItem> items) {
        return BotLiveEncoder.groundItems(items, null).stream()
                .collect(Collectors.toMap(MapEntityDto::serverIndex, MapEntityDto::id));
    }
}
