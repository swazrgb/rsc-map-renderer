package openrsc.livemap.panel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;

/** The selected-bot panel's parts, read from an account as the application knows it, logged in or not. */
public final class BotPanelEncoder {

    private BotPanelEncoder() {}

    /** @param bankSeen whether the bank has been opened, so that {@code s}'s bank is what it holds */
    public static BotPanelDto panel(AccountFacts s, String status, boolean bankSeen) {
        return new BotPanelDto(
                status,
                skills(s),
                inventory(s),
                s.inventory().usedSlots(),
                bank(s),
                s.bank().capacity(),
                bankSeen,
                quests(s),
                s.quests().questPoints());
    }

    /**
     * By skill name; the server counts experience in quarter points (level 2 at 332), the panel in whole ones.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Skills.java#L39">Skills.originalCurveExperienceArray</a>
     */
    public static Map<String, SkillDto> skills(AccountFacts s) {
        Stats st = s.stats();
        Map<String, SkillDto> out = new HashMap<>();
        for (Skill sk : Skill.values()) {
            out.put(sk.name(), new SkillDto(st.level(sk), st.baseLevel(sk), st.xp(sk) / 4));
        }
        return out;
    }

    public static List<InventoryItemDto> inventory(AccountFacts s) {
        List<InventorySlot> inv = s.inventory().slots();
        List<InventoryItemDto> out = new ArrayList<>(inv.size());
        for (int i = 0; i < inv.size(); i++) {
            InventorySlot it = inv.get(i);
            out.add(new InventoryItemDto(i, it.id(), it.amount(), it.equipped(), it.noted()));
        }
        return out;
    }

    public static List<BankSlotDto> bank(AccountFacts s) {
        List<BankSlot> bank = s.bank().slots();
        List<BankSlotDto> out = new ArrayList<>(bank.size());
        for (int i = 0; i < bank.size(); i++) {
            BankSlot it = bank.get(i);
            out.add(new BankSlotDto(i, it.id(), it.amount()));
        }
        return out;
    }

    /** Every quest in {@link QuestId}'s order. */
    public static List<QuestDto> quests(AccountFacts s) {
        List<QuestDto> out = new ArrayList<>(QuestId.values().length);
        for (QuestId q : QuestId.values()) {
            out.add(new QuestDto(q.id(), q.displayName(), s.quests().stage(q.id()), false, null));
        }
        return out;
    }
}
