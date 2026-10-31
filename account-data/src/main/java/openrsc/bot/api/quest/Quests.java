package openrsc.bot.api.quest;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

/** An account's quest stages, frozen; see {@link QuestId} for what a stage means. */
public final class Quests {

    public static final Quests EMPTY = new MutableQuests().snapshot();

    /** A quest-point total the server has not sent yet. */
    public static final int UNTOLD = -1;

    private final Int2IntMap stages;
    private final int questPoints;
    private final IntSet questIds;

    Quests(Int2IntMap stages, int serverQuestPoints) {
        this.stages = new Int2IntOpenHashMap(stages);
        this.stages.defaultReturnValue(QuestId.STAGE_NOT_STARTED);
        this.questIds = IntSets.unmodifiable(this.stages.keySet());
        this.questPoints = serverQuestPoints != UNTOLD ? serverQuestPoints : QuestPoints.total(this::stage);
    }

    /** The ids of every quest a stage is known for, not started ones included. */
    public IntSet questIds() {
        return questIds;
    }

    /** The stage of {@code questId}; {@link QuestId#STAGE_NOT_STARTED} when none is known. */
    public int stage(int questId) {
        return stages.get(questId);
    }

    public int stage(QuestId quest) {
        return stage(quest.id());
    }

    public boolean complete(int questId) {
        return QuestId.isCompleted(stage(questId));
    }

    public boolean complete(QuestId quest) {
        return complete(quest.id());
    }

    /**
     * The server's quest-point total, or until it has sent one, the total {@link QuestPoints#total}
     * derives from the stages.
     */
    public int questPoints() {
        return questPoints;
    }
}
