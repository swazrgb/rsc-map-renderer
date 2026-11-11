package openrsc.bot.api;

import java.util.List;
import openrsc.bot.api.Clan.ClanInfo;
import openrsc.bot.api.Clan.Invite;
import openrsc.bot.api.Clan.Member;
import org.jetbrains.annotations.Nullable;

public final class MutableClan {
    private boolean inClan;
    private @Nullable String clanName;
    private @Nullable String clanTag;
    private @Nullable String leaderName;
    private boolean isLeader;
    private List<Member> members = List.of();
    private int kickSetting;
    private int inviteSetting;
    private int allowSearchJoin;
    private int allowSetting0;
    private int allowSetting1;
    private @Nullable Invite lastInvite;
    private List<ClanInfo> searchList = List.of();
    private Clan snap = Clan.EMPTY;

    public void joined(String name, String tag, String leader, boolean leaderIsUs, List<Member> members) {
        snap = null;
        inClan = true;
        clanName = name;
        clanTag = tag;
        leaderName = leader;
        isLeader = leaderIsUs;
        this.members = List.copyOf(members);
    }

    public void left() {
        snap = null;
        inClan = false;
        clanName = null;
        clanTag = null;
        leaderName = null;
        isLeader = false;
        members = List.of();
    }

    public void invited(Invite invite) {
        snap = null;
        lastInvite = invite;
    }

    public void settings(int kick, int invite, int searchJoin, int allow0, int allow1) {
        snap = null;
        kickSetting = kick;
        inviteSetting = invite;
        allowSearchJoin = searchJoin;
        allowSetting0 = allow0;
        allowSetting1 = allow1;
    }

    public void searchResults(List<ClanInfo> list) {
        snap = null;
        searchList = List.copyOf(list);
    }

    public Clan snapshot() {
        Clan cached = snap;
        if (cached == null) {
            cached = new Clan(
                    inClan,
                    clanName,
                    clanTag,
                    leaderName,
                    isLeader,
                    members,
                    kickSetting,
                    inviteSetting,
                    allowSearchJoin,
                    allowSetting0,
                    allowSetting1,
                    lastInvite,
                    searchList);
            snap = cached;
        }
        return cached;
    }
}
