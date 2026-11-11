package openrsc.bot.core.net;

import java.util.Arrays;

public final class TickArrival {

    public static final int WINDOW = 128;

    public record Summary(int gaps, long p50Ms, long p95Ms, long maxMs, int late, long totalGaps, long totalLate) {}

    private final long[] gapsNs = new long[WINDOW];
    private int n;
    private boolean seen;
    private long lastNs;
    private long totalGaps;
    private long totalLate;
    private volatile Summary last;

    Summary note(long nowNs) {
        long prev = lastNs;
        lastNs = nowNs;
        if (!seen) {
            seen = true;
            return null;
        }
        gapsNs[n++] = nowNs - prev;
        if (n < WINDOW) {
            return null;
        }
        n = 0;
        long[] sorted = gapsNs.clone();
        Arrays.sort(sorted);
        long p50 = sorted[WINDOW / 2];
        long lateOver = p50 + p50 / 2;
        int late = 0;
        for (long g : sorted) {
            if (g > lateOver) {
                late++;
            }
        }
        totalGaps += WINDOW;
        totalLate += late;
        Summary s = new Summary(
                WINDOW, ms(p50), ms(sorted[WINDOW * 95 / 100]), ms(sorted[WINDOW - 1]), late, totalGaps, totalLate);
        last = s;
        return s;
    }

    public Summary last() {
        return last;
    }

    private static long ms(long ns) {
        return ns / 1_000_000L;
    }
}
