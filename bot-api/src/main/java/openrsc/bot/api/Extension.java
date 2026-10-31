package openrsc.bot.api;

import java.util.Set;
import openrsc.gamedata.WorldProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A server feature beyond authentic RSC, switched on by one key in the world's conf. */
public enum Extension {
    EQUIPMENT_TAB("want_equipment_tab"),

    CUSTOM_BANKS("want_custom_banks"),

    BANK_PRESETS("want_bank_presets"),

    BANK_NOTES("want_bank_notes"),

    CERT_DEPOSIT("want_cert_deposit"),

    QUICK_BANK("right_click_bank"),

    QUICK_TRADE("right_click_trade"),

    DROP_X("want_drop_x"),

    BATCHING("batch_progression"),

    AUCTION_HOUSE("spawn_auction_npcs"),

    IRONMAN("spawn_iron_man_npcs");

    private final String key;

    Extension(String key) {
        this.key = key;
    }

    /** The conf key that switches this on. */
    public @NotNull String key() {
        return key;
    }

    /** Whether {@code world}'s conf has this switched on. */
    public boolean on(@NotNull WorldProfile world) {
        return switch (this) {
            case EQUIPMENT_TAB -> world.rules().equipmentTab();
            case CUSTOM_BANKS -> world.rules().customBanks();
            case BANK_PRESETS -> world.rules().bankPresets();
            case BANK_NOTES -> world.rules().bankNotes();
            case CERT_DEPOSIT -> world.rules().certDeposit();
            case QUICK_BANK -> world.rules().quickBank();
            case QUICK_TRADE -> world.rules().quickTrade();
            case DROP_X -> world.rules().dropX();
            case BATCHING -> world.rules().batching();
            case AUCTION_HOUSE -> world.has(WorldProfile.Feature.AUCTION_NPCS);
            case IRONMAN -> world.has(WorldProfile.Feature.IRONMAN_NPCS);
        };
    }

    /**
     * The first of {@code needs}, in declaration order, that {@code world} lacks, or {@code null} when
     * it has them all.
     */
    public static @Nullable Extension missing(@NotNull Set<Extension> needs, @NotNull WorldProfile world) {
        if (needs.isEmpty()) {
            return null;
        }
        for (Extension e : values()) {
            if (needs.contains(e) && !e.on(world)) {
                return e;
            }
        }
        return null;
    }

    /** The message for {@code what} needing {@code missing} on a world without it. */
    public static @NotNull String violation(@NotNull Object what, @NotNull Extension missing) {
        return "EXTENDED: " + what + " needs " + missing.key();
    }
}
