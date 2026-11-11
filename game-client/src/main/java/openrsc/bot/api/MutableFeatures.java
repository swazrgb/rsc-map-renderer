package openrsc.bot.api;

import java.util.LinkedHashMap;
import java.util.List;
import openrsc.bot.api.Features.IronmanHud;
import openrsc.bot.api.Features.KillAnnouncement;
import openrsc.bot.api.Features.TrawlerHud;
import org.jetbrains.annotations.Nullable;

public final class MutableFeatures {
    private final LinkedHashMap<Integer, BankPreset> bankPresets = new LinkedHashMap<>();
    private long openPkPoints;
    private long openPkPointsToGpRatio;
    private boolean openPkSeen;
    private @Nullable IronmanHud ironman;
    private @Nullable TrawlerHud trawler;
    private boolean onBlackHole;
    private @Nullable KillAnnouncement killAnnouncement;
    private byte @Nullable [] pendingCaptcha;
    private int pendingCaptchaTick = Integer.MIN_VALUE;
    private Features snap = Features.EMPTY;

    public void bankPreset(BankPreset preset) {
        snap = null;
        bankPresets.put(preset.slot(), preset);
    }

    public void openPkPoints(long points) {
        snap = null;
        openPkPoints = points;
        openPkSeen = true;
    }

    public void openPkPointsToGpRatio(long ratio) {
        snap = null;
        openPkPointsToGpRatio = ratio;
        openPkSeen = true;
    }

    public void ironman(IronmanHud hud) {
        snap = null;
        ironman = hud;
    }

    public void trawler(TrawlerHud hud) {
        snap = null;
        trawler = hud;
    }

    public void onBlackHole(boolean on) {
        snap = null;
        onBlackHole = on;
    }

    public void killAnnouncement(KillAnnouncement k) {
        snap = null;
        killAnnouncement = k;
    }

    public void pendingCaptcha(byte @Nullable [] image, int tick) {
        snap = null;
        pendingCaptcha = image;
        pendingCaptchaTick = image == null ? Integer.MIN_VALUE : tick;
    }

    public Features snapshot() {
        Features cached = snap;
        if (cached == null) {
            cached = new Features(
                    List.copyOf(bankPresets.values()),
                    openPkPoints,
                    openPkPointsToGpRatio,
                    openPkSeen,
                    ironman,
                    trawler,
                    onBlackHole,
                    killAnnouncement,
                    pendingCaptcha,
                    pendingCaptchaTick);
            snap = cached;
        }
        return cached;
    }
}
