package openrsc.bot.api;

import openrsc.bot.core.login.PreLoginConfigs;
import org.jetbrains.annotations.Nullable;

public final class MutableHud {
    private boolean privacyKnown;
    private int privacyBlockChat;
    private int privacyBlockPrivate;
    private int privacyBlockTrade;
    private int privacyBlockDuel;
    private int gameCameraAuto;
    private int gameMouseButtonOne;
    private int gameSoundDisabled;
    private int[] gameCustomOptions = new int[0];
    private boolean welcomeKnown;
    private String welcomeLastIp = "";
    private int welcomeDaysSinceLogin;
    private int welcomeRecoveryDays;
    private int systemUpdateTimer;
    private int elixirTimer;
    private int experienceFrozen;
    private int experienceShared;
    private boolean bankPinOpen;
    private @Nullable String inputBoxPrompt;
    private int progressInterface;
    private int progressDelay;
    private int progressRepeat;
    private boolean wantBankNotes;
    private boolean wantDropX;
    private @Nullable PreLoginConfigs serverConfigs;
    private int combatStyle;
    private Hud snap = Hud.EMPTY;

    public void privacy(int blockChat, int blockPrivate, int blockTrade, int blockDuel) {
        snap = null;
        privacyKnown = true;
        privacyBlockChat = blockChat;
        privacyBlockPrivate = blockPrivate;
        privacyBlockTrade = blockTrade;
        privacyBlockDuel = blockDuel;
    }

    public void gameSettings(int cameraAuto, int mouseButtonOne, int soundDisabled, int[] custom) {
        snap = null;
        gameCameraAuto = cameraAuto;
        gameMouseButtonOne = mouseButtonOne;
        gameSoundDisabled = soundDisabled;
        gameCustomOptions = custom == null ? new int[0] : custom.clone();
    }

    public void welcome(String lastIp, int daysSinceLogin, int recoveryDays) {
        snap = null;
        welcomeKnown = true;
        welcomeLastIp = lastIp == null ? "" : lastIp;
        welcomeDaysSinceLogin = daysSinceLogin;
        welcomeRecoveryDays = recoveryDays;
    }

    public void systemUpdateTimer(int raw) {
        snap = null;
        systemUpdateTimer = raw;
    }

    public void elixirTimer(int raw) {
        snap = null;
        elixirTimer = raw;
    }

    public void experienceFrozen(int v) {
        snap = null;
        experienceFrozen = v;
    }

    public void experienceShared(int v) {
        snap = null;
        experienceShared = v;
    }

    public void bankPinOpen(boolean open) {
        snap = null;
        bankPinOpen = open;
    }

    public void inputBoxPrompt(@Nullable String prompt) {
        snap = null;
        inputBoxPrompt = prompt;
    }

    public void progress(int iface, int delay, int repeat) {
        snap = null;
        progressInterface = iface;
        progressDelay = delay;
        progressRepeat = repeat;
    }

    public void wantBankNotes(boolean want) {
        snap = null;
        wantBankNotes = want;
    }

    public void wantDropX(boolean want) {
        snap = null;
        wantDropX = want;
    }

    public void serverConfigs(PreLoginConfigs configs) {
        snap = null;
        serverConfigs = configs;
    }

    public void combatStyle(int style) {
        snap = null;
        combatStyle = style;
    }

    public Hud snapshot() {
        Hud cached = snap;
        if (cached == null) {
            cached = new Hud(
                    privacyKnown,
                    privacyBlockChat,
                    privacyBlockPrivate,
                    privacyBlockTrade,
                    privacyBlockDuel,
                    gameCameraAuto,
                    gameMouseButtonOne,
                    gameSoundDisabled,
                    gameCustomOptions,
                    welcomeKnown,
                    welcomeLastIp,
                    welcomeDaysSinceLogin,
                    welcomeRecoveryDays,
                    systemUpdateTimer,
                    elixirTimer,
                    experienceFrozen,
                    experienceShared,
                    bankPinOpen,
                    inputBoxPrompt,
                    progressInterface,
                    progressDelay,
                    progressRepeat,
                    wantBankNotes,
                    wantDropX,
                    combatStyle,
                    serverConfigs);
            snap = cached;
        }
        return cached;
    }
}
