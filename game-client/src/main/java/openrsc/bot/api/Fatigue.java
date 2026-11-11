package openrsc.bot.api;

public record Fatigue(
        int fatigueShort,
        int fatiguePercentShort,
        boolean sleeping,
        int sleepingFatigueShort,
        int sleepingFatiguePercent,
        int incorrectSleepWordCount,
        int lastSleepFatigueTick) {

    public static final Fatigue EMPTY = new Fatigue(0, 0, false, 0, 0, 0, Integer.MIN_VALUE);

    public double percent() {
        return fatigueShort * 100.0 / 750.0;
    }
}
