package openrsc.bot.api.quest;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

/** Quest progress being written quest by quest; read it through {@link #snapshot()}. Not thread-safe. */
public class MutableQuests {

    private final Int2IntMap stages = new Int2IntOpenHashMap();
    private int questPoints = Quests.UNTOLD;
    private Quests snapshot;

    public MutableQuests() {
        this.stages.defaultReturnValue(QuestId.STAGE_NOT_STARTED);
    }

    /** The current progress, frozen. The same instance is returned until the next change. */
    public Quests snapshot() {
        if (snapshot == null) {
            snapshot = new Quests(stages, questPoints);
        }

        return snapshot;
    }

    /**
     * Records {@code questId} at {@code stage}.
     *
     * @return whether that changed anything: false when the quest was already known at that stage
     */
    public boolean setStage(int questId, int stage) {
        if (stages.containsKey(questId) && stages.get(questId) == stage) {
            return false;
        }

        snapshot = null;
        stages.put(questId, stage);
        return true;
    }

    /** Records the server's quest-point total; {@link Quests#UNTOLD} until it is known. */
    public void setQuestPoints(int questPoints) {
        if (this.questPoints == questPoints) {
            return;
        }

        snapshot = null;
        this.questPoints = questPoints;
    }
}
