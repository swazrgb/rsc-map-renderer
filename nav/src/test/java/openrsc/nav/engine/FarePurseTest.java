package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FarePurseTest {

    private static final Point SHILO = Point.of(393, 837);
    private static final Area ARDOUGNE = Rect.of(555, 537, 559, 541);

    @Test
    @DisplayName("a bank stop never withdraws more of a fare than the account owns")
    void faresComeOutOfOneStock() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        MutableQuests quests = new MutableQuests();
        for (QuestId q : QuestId.values()) {
            quests.setStage(q.id(), -1);
        }
        AccountFacts account = new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                Bank.of(List.of(new BankSlot(0, ItemId.COINS, 515))),
                stats.snapshot(),
                quests.snapshot(),
                SHILO);
        // Real gates, not bypassed: the cart's own gate, carrying its 500, draws the first grant.
        NavPolicy policy = NavPolicy.DEFAULT;

        PlanResult r = pf.nav().plan(new PlanRequest(account, SHILO, ARDOUGNE, LoadoutSpec.any(), policy));

        if (r instanceof PlanResult.Planned p) {
            for (Route.Step step : p.route().steps()) {
                if (step instanceof Route.BankStop stop) {
                    stop.manifest().withdraw().forEach((item, target) -> {
                        long owned =
                                stop.pack().count(item.id()) + account.bank().count(item.id());
                        assertTrue(target <= owned, "withdraws " + target + " " + item + " of " + owned);
                    });
                }
                if (step instanceof Route.Hop hop) {
                    hop.fare()
                            .forEach((id, n) -> assertTrue(
                                    hop.pack().count(id) >= n,
                                    hop.transport() + " fare " + n + " of " + id + " unpaid"));
                }
            }
        }
    }
}
