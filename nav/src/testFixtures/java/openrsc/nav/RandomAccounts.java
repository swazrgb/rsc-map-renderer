package openrsc.nav;

import java.util.SplittableRandom;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;

/**
 * Accounts drawn from a seed: every skill 1 to 99, each of {@code gateQuests} unstarted, part way or done, some of
 * {@code gateItems} carried and banked, coins in both, standing anywhere on the surface.
 */
public final class RandomAccounts {

    private RandomAccounts() {}

    public static AccountFacts of(ServerData env, SplittableRandom rnd, int[] gateItems, int[] gateQuests) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            int level = 1 + rnd.nextInt(99);
            stats.setSkill(sk.ordinal(), level, level, 0);
        }
        MutableQuests quests = new MutableQuests();
        for (int q : gateQuests) {
            switch (rnd.nextInt(4)) {
                case 1 -> quests.setStage(q, 1 + rnd.nextInt(9));
                case 2 -> quests.setStage(q, -1);
                default -> {}
            }
        }
        MutableInventory inv = new MutableInventory();
        int slot = 0;
        inv.setSlot(slot, new InventorySlot(slot, ItemId.COINS.id(), rnd.nextInt(200), false));
        slot++;
        for (int id : gateItems) {
            if (slot < 30 && rnd.nextInt(8) == 0) {
                int amount = env.isStackable(id) ? 1 + rnd.nextInt(60) : 1;
                inv.setSlot(slot, new InventorySlot(slot, id, amount, rnd.nextInt(3) == 0));
                slot++;
            }
        }
        MutableBank bank = new MutableBank();
        int b = 0;
        bank.setSlot(b, new BankSlot(b, ItemId.COINS, rnd.nextInt(3000)));
        b++;
        for (int id : gateItems) {
            ItemId known = ItemId.getById(id);
            if (known != null && rnd.nextInt(3) == 0) {
                bank.setSlot(b, new BankSlot(b, known, env.isStackable(id) ? 1 + rnd.nextInt(300) : 1));
                b++;
            }
        }
        Point at = Point.of(50 + rnd.nextInt(850), 50 + rnd.nextInt(850));
        return new AccountFacts.Snapshot(env, inv.snapshot(), bank.snapshot(), stats.snapshot(), quests.snapshot(), at);
    }
}
