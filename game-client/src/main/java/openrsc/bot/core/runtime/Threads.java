package openrsc.bot.core.runtime;

public final class Threads {

    private static final boolean PLATFORM = Boolean.getBoolean("openrsc.platformThreads");

    private Threads() {}

    public static Thread.Builder named(String name) {
        return PLATFORM
                ? Thread.ofPlatform().daemon().name(name)
                : Thread.ofVirtual().name(name);
    }
}
