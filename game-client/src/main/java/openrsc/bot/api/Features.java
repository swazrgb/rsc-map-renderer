package openrsc.bot.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record Features(
        List<BankPreset> bankPresets,
        long openPkPoints,
        long openPkPointsToGpRatio,
        boolean openPkSeen,
        @Nullable IronmanHud ironman,
        @Nullable TrawlerHud trawler,
        boolean onBlackHole,
        @Nullable KillAnnouncement killAnnouncement,
        byte @Nullable [] pendingCaptcha,
        int pendingCaptchaTick) {

    public record IronmanHud(int interfaceId, int actionId, int type, int restriction) {}

    public record TrawlerHud(
            int interfaceId, int actionId, int waterLevel, int fishCaught, int minutesLeft, int netBroken) {}

    public record KillAnnouncement(String victim, String attacker, long killType) {}

    public static final Features EMPTY =
            new Features(List.of(), 0, 0, false, null, null, false, null, null, Integer.MIN_VALUE);
}
