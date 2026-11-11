package openrsc.bot.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record Clan(
        boolean inClan,
        @Nullable String clanName,
        @Nullable String clanTag,
        @Nullable String leaderName,
        boolean isLeader,
        List<Member> members,
        int kickSetting,
        int inviteSetting,
        int allowSearchJoin,
        int allowSetting0,
        int allowSetting1,
        @Nullable Invite lastInvite,
        List<ClanInfo> searchList) {

    public record Member(String name, int rank, boolean online) {}

    public record ClanInfo(
            int clanId, String name, String tag, int size, boolean allowsSearchedJoin, int points, int rank) {}

    public record Invite(String inviterName, String clanName) {}

    public static final Clan EMPTY =
            new Clan(false, null, null, null, false, List.of(), 0, 0, 0, 0, 0, null, List.of());
}
