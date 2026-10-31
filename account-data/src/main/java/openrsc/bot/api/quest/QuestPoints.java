package openrsc.bot.api.quest;

import java.util.function.IntUnaryOperator;

public final class QuestPoints {

    /**
     * The quest points each quest awards, indexed by server quest id. Must not be written to.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/shared/QuestRewardRegistrar.java#L37">QuestRewardRegistrar.init</a>
     */
    @SuppressWarnings("MutablePublicArray")
    public static final int[] REWARDS = {
        3, 1, 3, 1, 1, 5, 4, 1, 2, 3, 5, 1, 1, 1, 3, 1, 2, 4, 3, 1,
        4, 6, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 4, 1, 1, 1, 3, 1, 5,
        2, 5, 2, 2, 4, 1, 3, 2, 1, 4, 1, 2,
    };

    private QuestPoints() {}

    /**
     * The quest points of every quest whose stage, as {@code stageOf} answers it by quest id, is
     * complete; the server totals them the same way.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L1404">Player.calculateQuestPoints</a>
     */
    public static int total(IntUnaryOperator stageOf) {
        int total = 0;
        for (int q = 0; q < REWARDS.length; q++) {
            if (REWARDS[q] != 0 && stageOf.applyAsInt(q) < 0) {
                total += REWARDS[q];
            }
        }
        return total;
    }
}
