package openrsc.bot.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record SelfPanel(
        List<WornItem> equipment,
        int[] equipmentStats,
        int[] prayersActive,
        Appearance appearance,
        @Nullable UnlockedAppearances unlockedAppearances) {

    public static final SelfPanel EMPTY = new SelfPanel(List.of(), new int[5], new int[0], Appearance.UNKNOWN, null);
}
