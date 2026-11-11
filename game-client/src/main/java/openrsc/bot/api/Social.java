package openrsc.bot.api;

import java.util.List;

public record Social(List<FriendEntry> friends, List<String> ignoreList, List<OnlinePlayer> onlineList) {

    public static final Social EMPTY = new Social(List.of(), List.of(), List.of());
}
