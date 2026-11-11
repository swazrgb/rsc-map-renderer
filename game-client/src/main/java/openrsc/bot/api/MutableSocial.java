package openrsc.bot.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class MutableSocial {
    private final LinkedHashMap<String, FriendEntry> friends = new LinkedHashMap<>();

    private List<String> ignoreList = List.of();
    private List<OnlinePlayer> onlineList = List.of();
    private Social snap = Social.EMPTY;

    public void friendUpdated(FriendEntry entry) {
        snap = null;
        friends.put(entry.name().toLowerCase(Locale.ROOT), entry);
    }

    public void ignoreList(List<String> names) {
        snap = null;
        ignoreList = List.copyOf(names);
    }

    public void onlineList(List<OnlinePlayer> list) {
        snap = null;
        onlineList = List.copyOf(list);
    }

    public Social snapshot() {
        Social cached = snap;
        if (cached == null) {
            cached = new Social(List.copyOf(friends.values()), ignoreList, onlineList);
            snap = cached;
        }
        return cached;
    }
}
