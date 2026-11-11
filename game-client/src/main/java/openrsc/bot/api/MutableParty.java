package openrsc.bot.api;

import java.util.List;
import openrsc.bot.api.Party.Invite;
import openrsc.bot.api.Party.Member;
import openrsc.bot.api.Party.PartyInfo;
import org.jetbrains.annotations.Nullable;

public final class MutableParty {
    private boolean inParty;
    private @Nullable String leaderName;
    private boolean isLeader;
    private List<Member> members = List.of();
    private int kickSetting;
    private int inviteSetting;
    private int allowSearchJoin;
    private int allowSetting0;
    private int allowSetting1;
    private @Nullable Invite lastInvite;
    private List<PartyInfo> searchList = List.of();
    private Party snap = Party.EMPTY;

    public void joined(String leader, boolean leaderIsUs, List<Member> members) {
        snap = null;
        inParty = true;
        leaderName = leader;
        isLeader = leaderIsUs;
        this.members = List.copyOf(members);
    }

    public void left() {
        snap = null;
        inParty = false;
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

    public void searchResults(List<PartyInfo> list) {
        snap = null;
        searchList = List.copyOf(list);
    }

    public Party snapshot() {
        Party cached = snap;
        if (cached == null) {
            cached = new Party(
                    inParty,
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
