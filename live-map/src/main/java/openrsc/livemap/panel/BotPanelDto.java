package openrsc.livemap.panel;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * A bot as the selected-bot panel shows it: its skills by name ({@code xp} in whole points), what it carries and has
 * banked by slot, and its quests by stage. {@code bankSeen} is false until the bank has been opened, when an empty
 * {@code bank} says nothing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BotPanelDto(
        String status,
        Map<String, SkillDto> skills,
        List<InventoryItemDto> inventory,
        int packSlotsUsed,
        List<BankSlotDto> bank,
        int bankMaxSize,
        boolean bankSeen,
        List<QuestDto> quests,
        int questPoints) {}
