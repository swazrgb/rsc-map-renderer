package openrsc.bot.api;

import openrsc.bot.core.login.PreLoginConfigs;
import org.jetbrains.annotations.Nullable;

/**
 * @param privacyKnown whether this session's privacy settings are known, sent by the server or set by the bot; until
 *     then they read 0
 */
public record Hud(
        boolean privacyKnown,
        int privacyBlockChat,
        int privacyBlockPrivate,
        int privacyBlockTrade,
        int privacyBlockDuel,
        int gameCameraAuto,
        int gameMouseButtonOne,
        int gameSoundDisabled,
        int[] gameCustomOptions,
        boolean welcomeKnown,
        String welcomeLastIp,
        int welcomeDaysSinceLogin,
        int welcomeRecoveryDays,
        int systemUpdateTimer,
        int elixirTimer,
        int experienceFrozen,
        int experienceShared,
        boolean bankPinOpen,
        @Nullable String inputBoxPrompt,
        int progressInterface,
        int progressDelay,
        int progressRepeat,
        boolean wantBankNotes,
        boolean wantDropX,
        int combatStyle,
        @Nullable PreLoginConfigs serverConfigs) {

    public static final Hud EMPTY = new Hud(
            false,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            new int[0],
            false,
            "",
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            null,
            0,
            0,
            0,
            false,
            false,
            0,
            null);

    public boolean batchBarUp() {
        return progressInterface == 1 || progressInterface == 3;
    }
}
