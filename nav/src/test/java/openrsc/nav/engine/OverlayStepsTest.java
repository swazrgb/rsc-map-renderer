package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.ChunkBoxOverlay;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.LocalOverlay;
import openrsc.gamedata.world.WallOverlay;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class OverlayStepsTest {

    private record Plain(LocalOverlay inner) implements LocalOverlay {
        @Override
        public int flags(int x, int y) {
            return inner.flags(x, y);
        }

        @Override
        public boolean isLive(int x, int y) {
            return inner.isLive(x, y);
        }
    }

    @Test
    void aFrozenViewAnswersEveryStepAsTheRulesDo() {
        CollisionMap map = RealDefs.collisionMap();
        SplittableRandom rnd = new SplittableRandom(17);
        // Lumbridge, the y = 512 cluster seam, Falador, the far south-west, and the map's corner.
        int[][] spots = {{122, 648}, {215, 512}, {310, 545}, {651, 848}, {2, 2}};
        List<String> wrong = new ArrayList<>();
        for (int[] at : spots) {
            for (int variant = 0; variant < 4; variant++) {
                WallOverlay live = new WallOverlay();
                live.moveTo(ChunkBox.around(at[0], at[1], 3));
                for (int i = 0; i < variant * 25; i++) {
                    int x = at[0] - 28 + rnd.nextInt(56);
                    int y = at[1] - 28 + rnd.nextInt(56);
                    switch (rnd.nextInt(3)) {
                        case 0 -> live.addBoundary(x, y, rnd.nextInt(4));
                        case 1 -> live.addSceneryFullBlock(x, y, 0, 1 + rnd.nextInt(2), 1 + rnd.nextInt(2));
                        default -> live.clearChunk(x >> 3, y >> 3);
                    }
                }
                ChunkBoxOverlay box = live.snapshot();
                LocalOverlay rules = new Plain(box);
                for (int y = at[1] - 40; y <= at[1] + 40; y++) {
                    for (int x = at[0] - 40; x <= at[0] + 40; x++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dx = -1; dx <= 1; dx++) {
                                boolean viaBox = map.canStep(x, y, x + dx, y + dy, box);
                                if (viaBox != map.canStep(x, y, x + dx, y + dy, rules) && wrong.size() < 20) {
                                    wrong.add("near " + at[0] + "," + at[1] + " variant " + variant + ": (" + x + ","
                                            + y + ") -> (" + (x + dx) + "," + (y + dy) + "): box says " + viaBox);
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), wrong);
    }
}
