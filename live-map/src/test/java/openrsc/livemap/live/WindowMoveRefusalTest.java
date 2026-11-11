package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Objects;
import openrsc.bot.api.MutableGameState;
import openrsc.bot.api.SessionKey;
import openrsc.bot.api.WindowMove;
import openrsc.bot.api.WornItem;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.packets.BankInsertPacket;
import openrsc.bot.api.packets.BankPresetLoadPacket;
import openrsc.bot.api.packets.BankSwapPacket;
import openrsc.bot.api.packets.DepositAllPacket;
import openrsc.bot.api.packets.DepositAllWornPacket;
import openrsc.bot.api.packets.DepositPacket;
import openrsc.bot.api.packets.DuelRulesPacket;
import openrsc.bot.api.packets.ItemRemoveToBankPacket;
import openrsc.bot.api.packets.TradeOfferPacket;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.WallOverlay;
import openrsc.livemap.dto.WindowMoveDto;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WindowMoveRefusalTest {

    private static final int COINS = 10;
    private static final ServerData DATA = RealDefs.serverData();
    private static final List<TradeOfferPacket.Offer> FIVE_COINS = List.of(new TradeOfferPacket.Offer(COINS, 5));

    private static MutableGameState world() {
        MutableGameState w = new MutableGameState(
                DATA,
                SessionKey.ofAccount("moves"),
                new WallOverlay(),
                RealDefs.pathfinder().nav());
        w.setSelfCoords(122, 647, 0);
        return w;
    }

    private static WindowMove trade(String action) {
        return Objects.requireNonNull(WindowMove.trade(DATA, action, FIVE_COINS));
    }

    @Test
    void aTradeMoveWantsItsScreen() {
        MutableGameState w = world();
        assertEquals("no trade is open", trade("decline").refusal(w.freezeTick()));

        w.openTrade(7);
        assertNull(trade("offer").refusal(w.freezeTick()));
        assertNull(trade("accept").refusal(w.freezeTick()));
        assertEquals("the trade is not on its confirm screen", trade("confirm").refusal(w.freezeTick()));

        w.setTradeAccepted(true);
        w.setTradeOtherAccepted(true);
        assertEquals("both sides have accepted", trade("offer").refusal(w.freezeTick()));

        w.setTradeConfirmScreen("bob", List.of(new InventorySlot(0, COINS, 5, false)), List.of());
        assertNull(trade("confirm").refusal(w.freezeTick()));
        assertNull(trade("decline").refusal(w.freezeTick()));
        assertEquals("the trade is on its confirm screen", trade("accept").refusal(w.freezeTick()));
    }

    @Test
    void aDuelsRulesAreSetOnItsFirstScreen() {
        MutableGameState w = world();
        WindowMove rules = Objects.requireNonNull(
                WindowMove.duel(DATA, "rules", null, new DuelRulesPacket(false, true, false, false)));
        assertEquals("no duel is open", rules.refusal(w.freezeTick()));
        w.duel().windowOpened(3);
        assertNull(rules.refusal(w.freezeTick()));
    }

    @Test
    void anUntradableItemIsNeverOffered() {
        int untradable = 0;
        while (DATA.item(untradable) == null || !DATA.item(untradable).untradable()) {
            untradable++;
        }
        List<TradeOfferPacket.Offer> offer = List.of(new TradeOfferPacket.Offer(untradable, 1));
        assertNull(WindowMove.trade(DATA, "offer", offer));
        assertNull(WindowMove.duel(DATA, "offer", offer, null));
    }

    @Test
    void theBanksOwnMovesAreMadeOnlyWhereTheWorldHasThem() {
        int sword = 70;
        List<String> coleslawOnly =
                List.of("deposit_inventory", "deposit_worn", "unequip", "swap", "insert", "load_preset", "save_preset");
        for (String action : coleslawOnly) {
            assertNull(WindowMove.bank(DATA, action, sword, 1, false, 0, 1), action);
        }
        assertNull(WindowMove.bank(DATA, "withdraw", COINS, 5, true, 0, 0), "no notes");

        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        assertEquals(
                List.of(
                        new DepositAllPacket(),
                        new DepositAllWornPacket(),
                        new ItemRemoveToBankPacket(4),
                        new BankSwapPacket(0, 1),
                        new BankInsertPacket(0, 1),
                        new BankPresetLoadPacket(1)),
                List.of("deposit_inventory", "deposit_worn", "unequip", "swap", "insert", "load_preset").stream()
                        .map(a -> Objects.requireNonNull(
                                        WindowMove.bank(coleslaw, a, sword, 1, false, a.endsWith("preset") ? 1 : 0, 1),
                                        a)
                                .packet())
                        .toList());
        assertNull(WindowMove.bank(coleslaw, "swap", sword, 1, false, 3, 3), "a stack onto itself");
        assertNull(WindowMove.bank(coleslaw, "insert", sword, 1, false, -1, 3));
        assertNull(WindowMove.bank(coleslaw, "load_preset", sword, 1, false, WindowMove.PRESETS, 0));
        assertNull(WindowMove.bank(coleslaw, "unequip", COINS, 1, false, 0, 0), "coins are not worn");
    }

    @Test
    void anUnequipToTheBankWantsSomethingWornThere() {
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        MutableGameState w = new MutableGameState(
                coleslaw,
                SessionKey.ofAccount("unequip"),
                new WallOverlay(),
                RealDefs.pathfinder(RealDefs.COLESLAW).nav());
        w.setSelfCoords(122, 647, 0);
        w.setBankOpen(true);
        WindowMove unequip = Objects.requireNonNull(WindowMove.bank(coleslaw, "unequip", 70, 1, false, 0, 0));
        assertEquals("nothing is worn there", unequip.refusal(w.freezeTick()));
        w.setEquipment(List.of(new WornItem(4, 70, 1)));
        assertNull(unequip.refusal(w.freezeTick()));
    }

    @Test
    void aBankMoveSentWithoutSlotsIsReadAsOneWithNone() {
        WindowMoveDto.Bank deposit = JsonMapper.builder()
                .build()
                .readValue(
                        "{\"action\":\"deposit\",\"item\":10,\"amount\":5,\"noted\":false}", WindowMoveDto.Bank.class);
        assertEquals(
                new DepositPacket(COINS, 5),
                Objects.requireNonNull(deposit.move(DATA)).packet());
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        assertNull(new WindowMoveDto.Bank("swap", 0, 0, false, null, 4).move(coleslaw));
        assertNull(new WindowMoveDto.Bank("load_preset", 0, 0, false, null, null).move(coleslaw));
    }

    @Test
    void aMoveWithoutAnActionIsNoMove() {
        assertNull(WindowMove.trade(DATA, null, FIVE_COINS));
        assertNull(WindowMove.duel(DATA, null, FIVE_COINS, null));
        assertNull(WindowMove.bank(DATA, null, COINS, 1, false, 0, 0));
    }
}
