package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.LocalOverlay;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class StaticStepsTest {

    private static final LocalOverlay NOTHING = new LocalOverlay() {
        @Override
        public int flags(int x, int y) {
            return 0;
        }

        @Override
        public boolean isLive(int x, int y) {
            return false;
        }
    };

    @Test
    void everyBakedStepIsTheRulesAnswer() {
        CollisionMap map = RealDefs.collisionMap();
        List<String> wrong = new ArrayList<>();
        long allowed = 0;
        for (int y = -1; y <= CollisionMap.HEIGHT; y++) {
            for (int x = -1; x <= CollisionMap.WIDTH; x++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        boolean baked = map.canStep(x, y, x + dx, y + dy);
                        if (baked != map.canStep(x, y, x + dx, y + dy, NOTHING)) {
                            if (wrong.size() < 20) {
                                wrong.add(
                                        "(" + x + "," + y + ") -> (" + (x + dx) + "," + (y + dy) + "): baked " + baked);
                            }
                        }
                        allowed += baked ? 1 : 0;
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
        assertEquals(true, allowed > 1_000_000, "the map has walkable steps: " + allowed);
    }
}
