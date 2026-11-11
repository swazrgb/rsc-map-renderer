package openrsc.bot.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record Party(
        boolean inParty,
        @Nullable String leaderName,
        boolean isLeader,
        List<Member> members,
        int kickSetting,
        int inviteSetting,
        int allowSearchJoin,
        int allowSetting0,
        int allowSetting1,
        @Nullable Invite lastInvite,
        List<PartyInfo> searchList) {

    public record Member(
            String name,
            int rank,
            boolean online,
            int currentHits,
            int maxHits,
            int combatLevel,
            boolean skulled,
            boolean dead,
            boolean shareLoot,
            int totalLevel,
            boolean inCombat,
            int shareExp,
            long shareExpAccrued) {}

    public record PartyInfo(int partyId, int size, boolean allowsSearchedJoin, int points, int rank) {}

    public record Invite(String inviterName, String partyName) {}

    public static final Party EMPTY = new Party(false, null, false, List.of(), 0, 0, 0, 0, 0, null, List.of());
}
