package openrsc.bot.api.quest;

import java.util.HashMap;
import java.util.Map;

/**
 * The server's quest ids. A stage is 0 before a quest starts, positive while it is under way and
 * negative once it is complete, so test completion with {@link #isCompleted(int)}: Shield of Arrav
 * completes at -1 or -2, by gang.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Quests.java#L4">Quests.BLACK_KNIGHTS_FORTRESS</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L1404">Player.calculateQuestPoints</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L1416">Player.getQuestStage</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/ShieldOfArrav.java#L32">ShieldOfArrav.BLACK_ARM_COMPLETE</a>
 */
public enum QuestId {
    BLACK_KNIGHTS_FORTRESS(0, "Black Knight's Fortress"),
    COOKS_ASSISTANT(1, "Cook's Assistant"),
    DEMON_SLAYER(2, "Demon Slayer"),
    DORICS_QUEST(3, "Doric's Quest"),
    THE_RESTLESS_GHOST(4, "The Restless Ghost"),
    GOBLIN_DIPLOMACY(5, "Goblin Diplomacy"),
    ERNEST_THE_CHICKEN(6, "Ernest the Chicken"),
    IMP_CATCHER(7, "Imp Catcher"),
    PIRATES_TREASURE(8, "Pirate's Treasure"),
    PRINCE_ALI_RESCUE(9, "Prince Ali Rescue"),
    ROMEO_N_JULIET(10, "Romeo & Juliet"),
    SHEEP_SHEARER(11, "Sheep Shearer"),
    SHIELD_OF_ARRAV(12, "Shield of Arrav"),
    THE_KNIGHTS_SWORD(13, "The Knight's Sword"),
    VAMPIRE_SLAYER(14, "Vampire Slayer"),
    WITCHS_POTION(15, "Witch's Potion"),
    DRAGON_SLAYER(16, "Dragon Slayer"),
    WITCHS_HOUSE(17, "Witch's House"),
    LOST_CITY(18, "Lost City"),
    HEROS_QUEST(19, "Hero's Quest"),
    DRUIDIC_RITUAL(20, "Druidic Ritual"),
    MERLINS_CRYSTAL(21, "Merlin's Crystal"),
    SCORPION_CATCHER(22, "Scorpion Catcher"),
    FAMILY_CREST(23, "Family Crest"),
    TRIBAL_TOTEM(24, "Tribal Totem"),
    FISHING_CONTEST(25, "Fishing Contest"),
    MONKS_FRIEND(26, "Monk's Friend"),
    TEMPLE_OF_IKOV(27, "Temple of Ikov"),
    CLOCK_TOWER(28, "Clock Tower"),
    THE_HOLY_GRAIL(29, "The Holy Grail"),
    FIGHT_ARENA(30, "Fight Arena"),
    TREE_GNOME_VILLAGE(31, "Tree Gnome Village"),
    THE_HAZEEL_CULT(32, "The Hazeel Cult"),
    SHEEP_HERDER(33, "Sheep Herder"),
    PLAGUE_CITY(34, "Plague City"),
    SEA_SLUG(35, "Sea Slug"),
    WATERFALL_QUEST(36, "Waterfall Quest"),
    BIOHAZARD(37, "Biohazard"),
    JUNGLE_POTION(38, "Jungle Potion"),
    GRAND_TREE(39, "The Grand Tree"),
    SHILO_VILLAGE(40, "Shilo Village"),
    UNDERGROUND_PASS(41, "Underground Pass"),
    OBSERVATORY_QUEST(42, "Observatory Quest"),
    TOURIST_TRAP(43, "Tourist Trap"),
    WATCHTOWER(44, "Watchtower"),
    DWARF_CANNON(45, "Dwarf Cannon"),
    MURDER_MYSTERY(46, "Murder Mystery"),
    DIGSITE(47, "Digsite"),
    GERTRUDES_CAT(48, "Gertrude's Cat"),
    LEGENDS_QUEST(49, "Legends Quest"),
    RUNE_MYSTERIES(50, "Rune Mysteries"),
    PEELING_THE_ONION(51, "Peeling the Onion");

    public static final int STAGE_NOT_STARTED = 0;
    public static final int STAGE_COMPLETED = -1;
    /** Shield of Arrav's completed stage for the Black Arm gang; {@link #STAGE_COMPLETED} is the Phoenix one. */
    public static final int STAGE_COMPLETED_BLACK_ARM = -2;

    private static final Map<Integer, QuestId> BY_ID = new HashMap<>();

    static {
        for (QuestId q : values()) {
            BY_ID.put(q.questId, q);
        }
    }

    private final int questId;
    private final String displayName;

    QuestId(int questId, String displayName) {
        this.questId = questId;
        this.displayName = displayName;
    }

    public int id() {
        return questId;
    }

    public String displayName() {
        return displayName;
    }

    public static boolean isCompleted(int stage) {
        return stage < 0;
    }

    /** The quest with server id {@code id}, or {@code null}. */
    public static QuestId getById(int id) {
        return BY_ID.get(id);
    }

    /** The display name of {@code questId}, or {@code "quest <id>"} for an unknown one. */
    public static String nameOf(int questId) {
        QuestId q = BY_ID.get(questId);
        return q != null ? q.displayName : "quest " + questId;
    }
}
