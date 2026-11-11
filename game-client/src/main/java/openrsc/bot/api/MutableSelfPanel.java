package openrsc.bot.api;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class MutableSelfPanel {
    private List<WornItem> equipment = List.of();
    private int[] equipmentStats = new int[5];
    private int[] prayersActive = new int[0];
    private Appearance appearance = Appearance.UNKNOWN;
    private @Nullable UnlockedAppearances unlockedAppearances;
    private SelfPanel snap = SelfPanel.EMPTY;

    public void equipment(List<WornItem> items) {
        snap = null;
        equipment = List.copyOf(items);
    }

    public void equipmentSlot(int wieldPos, int catalogId, int amount) {
        snap = null;
        List<WornItem> next = new ArrayList<>(equipment);
        next.removeIf(w -> w.wieldPos() == wieldPos);
        if (catalogId >= 0) {
            next.add(new WornItem(wieldPos, catalogId, amount));
        }
        equipment = List.copyOf(next);
    }

    public void equipmentStats(int[] stats) {
        snap = null;
        equipmentStats = stats.clone();
    }

    public void prayersActive(int[] flags) {
        snap = null;
        prayersActive = flags.clone();
    }

    public void appearance(Appearance look) {
        snap = null;
        appearance = look == null ? Appearance.UNKNOWN : look;
    }

    public void unlockedAppearances(UnlockedAppearances u) {
        snap = null;
        unlockedAppearances = u;
    }

    public Appearance appearance() {
        return appearance;
    }

    public SelfPanel snapshot() {
        SelfPanel cached = snap;
        if (cached == null) {
            cached = new SelfPanel(equipment, equipmentStats, prayersActive, appearance, unlockedAppearances);
            snap = cached;
        }
        return cached;
    }
}
