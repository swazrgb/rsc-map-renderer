package openrsc.bot.api.store;

/**
 * A typed handle to one value in a bot's persistent store.
 *
 * <p>The store addresses a value by {@link #name()}: two keys with the same name share it, and a
 * renamed key no longer finds what was stored under the old name.
 */
public final class BotKey<T> {

    private final String name;
    private final Class<T> type;

    private BotKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    public static <T> BotKey<T> of(String name, Class<T> type) {
        return new BotKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "BotKey(" + name + ")";
    }
}
