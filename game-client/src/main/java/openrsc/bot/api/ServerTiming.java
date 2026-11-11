package openrsc.bot.api;

public record ServerTiming(int gameTickMs, boolean scalesGameDurations) {

    public static final int AUTHENTIC_TICK_MS = 640;

    public static final ServerTiming AUTHENTIC = new ServerTiming(AUTHENTIC_TICK_MS, true);

    public ServerTiming {
        if (gameTickMs <= 0) {
            throw new IllegalArgumentException("gameTickMs must be positive, got " + gameTickMs);
        }
    }

    public int gameDurationDivisorMs() {
        return scalesGameDurations ? AUTHENTIC_TICK_MS : gameTickMs;
    }

    public int ticksForSeconds(int seconds) {
        return ticksForMillis(seconds * 1000L);
    }

    public int ticksForMillis(long millis) {
        return (int) Math.ceil(millis / (double) gameDurationDivisorMs());
    }
}
