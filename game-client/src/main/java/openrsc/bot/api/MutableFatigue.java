package openrsc.bot.api;

public final class MutableFatigue {
    private int fatigueShort;
    private int fatiguePercentShort;
    private boolean sleeping;
    private int sleepingFatigueShort;
    private int sleepingFatiguePercent;
    private int incorrectSleepWordCount;
    private int lastSleepFatigueTick = Integer.MIN_VALUE;
    private Fatigue snap = Fatigue.EMPTY;

    public void fatigue(int percentShort, int authenticShort) {
        snap = null;
        fatiguePercentShort = percentShort;
        fatigueShort = authenticShort;
    }

    public void sleepingFatigue(int percentShort, int authenticShort) {
        snap = null;
        fatiguePercentShort = percentShort;
        fatigueShort = authenticShort;
        sleepingFatigueShort = authenticShort;
        sleepingFatiguePercent = percentShort;
    }

    public void sleeping(boolean asleep) {
        snap = null;
        sleeping = asleep;
        if (!asleep) {
            lastSleepFatigueTick = Integer.MIN_VALUE;
        }
    }

    public void incorrectSleepWord() {
        snap = null;
        incorrectSleepWordCount++;
    }

    public void resetIncorrectSleepWord() {
        if (incorrectSleepWordCount != 0) {
            snap = null;
            incorrectSleepWordCount = 0;
        }
    }

    public void sleepFatiguePush(int tick) {
        snap = null;
        lastSleepFatigueTick = tick;
    }

    public boolean sleeping() {
        return sleeping;
    }

    public int fatigueShort() {
        return fatigueShort;
    }

    public int sleepingFatigueShort() {
        return sleepingFatigueShort;
    }

    public Fatigue snapshot() {
        Fatigue cached = snap;
        if (cached == null) {
            cached = new Fatigue(
                    fatigueShort,
                    fatiguePercentShort,
                    sleeping,
                    sleepingFatigueShort,
                    sleepingFatiguePercent,
                    incorrectSleepWordCount,
                    lastSleepFatigueTick);
            snap = cached;
        }
        return cached;
    }
}
