package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import openrsc.bot.api.BankPreset;
import openrsc.bot.api.Duel;
import openrsc.bot.api.MutableGameState;
import openrsc.bot.api.SessionKey;
import openrsc.bot.api.SessionTick;
import openrsc.bot.api.WindowMove;
import openrsc.bot.api.WornItem;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.packets.BankPresetSavePacket;
import openrsc.gamedata.world.WallOverlay;
import openrsc.livemap.dto.WindowsDto;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class BotLiveEncoderWindowsTest {

    private static final int COINS = 10;
    private static final int SHARK = 546;
    private static final int BRONZE_SWORD = 70;

    private static MutableGameState world() {
        MutableGameState w = new MutableGameState(
                RealDefs.serverData(),
                SessionKey.ofAccount("windows"),
                new WallOverlay(),
                RealDefs.pathfinder().nav());
        w.setSelfCoords(122, 647, 0);
        return w;
    }

    @Test
    void noScreenOpenIsNoWindows() {
        assertNull(BotLiveEncoder.windows(world().freezeTick()));
    }

    @Test
    void theBankIsOpenAlone() {
        MutableGameState w = world();
        w.setBankOpen(true);
        assertEquals(
                new WindowsDto(new WindowsDto.Bank(false, true, false, false, false, false, null), null, null),
                BotLiveEncoder.windows(w.freezeTick()));
    }

    @Test
    void aColeslawBankListsEveryPresetAndOneSavedThereAsTheServerKeepsIt() {
        MutableGameState w = new MutableGameState(
                RealDefs.serverData(RealDefs.COLESLAW),
                SessionKey.ofAccount("presets"),
                new WallOverlay(),
                RealDefs.pathfinder(RealDefs.COLESLAW).nav());
        w.setSelfCoords(122, 647, 0);
        w.setBankOpen(true);
        w.putBankPreset(new BankPreset(1, List.of(new InventorySlot(0, SHARK, 1, false)), List.of()));
        WindowsDto.Bank bank = BotLiveEncoder.windows(w.freezeTick()).bank();
        assertEquals(
                List.of(true, false, true, true, true, true),
                List.of(
                        bank.notes(),
                        bank.wornDeposits(),
                        bank.depositAll(),
                        bank.depositWorn(),
                        bank.unequip(),
                        bank.rearrange()));
        assertEquals(
                List.of(
                        new WindowsDto.Preset(0, false, List.of(), List.of()),
                        new WindowsDto.Preset(1, true, List.of(new WindowsDto.Stack(SHARK, 1, false)), List.of())),
                bank.presets(),
                "the one the server has not sent is listed too");

        w.setInventory(List.of(new InventorySlot(0, COINS, 40, false)));
        w.setEquipment(List.of(new WornItem(4, BRONZE_SWORD, 1)));
        new BankPresetSavePacket(0).noteSent(w, new SessionTick(SessionKey.ofAccount("presets"), 5));
        new BankPresetSavePacket(WindowMove.PRESETS).noteSent(w, new SessionTick(SessionKey.ofAccount("presets"), 5));
        assertEquals(
                new WindowsDto.Preset(
                        0,
                        true,
                        List.of(new WindowsDto.Stack(COINS, 40, false)),
                        List.of(new WindowsDto.Stack(BRONZE_SWORD, 1, false))),
                BotLiveEncoder.windows(w.freezeTick()).bank().presets().get(0),
                "what is worn saved apart from the pack");
        assertEquals(
                WindowMove.PRESETS,
                BotLiveEncoder.windows(w.freezeTick()).bank().presets().size());
    }

    @Test
    void aTradesFirstScreenThenItsConfirmScreen() {
        MutableGameState w = world();
        w.openTrade(7);
        w.setTradeOffers(
                List.of(new InventorySlot(0, COINS, 40, false)),
                List.of(new InventorySlot(0, SHARK, 1, false), new InventorySlot(1, SHARK, 1, false)));
        w.setTradeAccepted(true);
        assertEquals(
                new WindowsDto.Trade(
                        "",
                        List.of(new WindowsDto.Stack(COINS, 40, false)),
                        List.of(new WindowsDto.Stack(SHARK, 1, false), new WindowsDto.Stack(SHARK, 1, false)),
                        true,
                        false,
                        false),
                BotLiveEncoder.windows(w.freezeTick()).trade(),
                "the first screen, its partner out of view");

        w.setTradeConfirmScreen(
                "bob", List.of(new InventorySlot(0, COINS, 40, false)), List.of(new InventorySlot(0, SHARK, 2, false)));
        WindowsDto.Trade confirm = BotLiveEncoder.windows(w.freezeTick()).trade();
        assertEquals("bob", confirm.partner());
        assertEquals(List.of(new WindowsDto.Stack(SHARK, 2, false)), confirm.theirs());
        assertEquals(true, confirm.confirm());
    }

    @Test
    void aDuelCarriesItsRulesAndTheStakeThePlayerSent() {
        MutableGameState w = world();
        w.duel().windowOpened(3);
        w.duel().myStake(List.of(new Duel.StakeItem(COINS, false, 10)));
        w.duel().settings(1, 1, 0, 0);
        WindowsDto.Duel duel = BotLiveEncoder.windows(w.freezeTick()).duel();
        assertEquals(List.of(new WindowsDto.Stack(COINS, 10, false)), duel.mine());
        assertEquals(
                List.of(true, true, false, false),
                List.of(duel.noRetreat(), duel.noMagic(), duel.noPrayer(), duel.noWeapons()));
        assertEquals(false, duel.confirm());
    }

    @Test
    void aTradesAcceptsShowAsTheGamesClientShowsThem() {
        MutableGameState w = world();
        w.openTrade(7);
        w.setTradeOtherAccepted(true);
        w.noteTradeAcceptSent();
        assertEquals(List.of(true, true), shown(w));

        w.setTradeOffers(List.of(), List.of(new InventorySlot(0, SHARK, 1, false)));
        assertEquals(List.of(false, false), shown(w), "the other's offer, as it arrives");
        assertEquals(
                List.of(true, true),
                List.of(
                        w.freezeTick().trade().accepted(),
                        w.freezeTick().trade().otherAccepted()),
                "the server's word, which the bot plays by");

        w.setTradeOtherAccepted(true);
        w.noteTradeOfferSent();
        assertEquals(List.of(false, false), shown(w), "an offer of ours, as it is sent");
    }

    private static List<Boolean> shown(MutableGameState w) {
        WindowsDto.Trade trade = BotLiveEncoder.windows(w.freezeTick()).trade();
        return List.of(trade.accepted(), trade.theyAccepted());
    }

    @Test
    void aChangedStakeOrRuleTakesBackBothAcceptsOfADuel() {
        MutableGameState w = world();
        w.duel().windowOpened(3);
        for (Runnable change : List.<Runnable>of(
                () -> w.duel().opponentStake(List.of(new Duel.StakeItem(COINS, false, 5))),
                () -> w.duel().myStake(List.of(new Duel.StakeItem(SHARK, false, 1))),
                () -> w.duel().settings(0, 0, 1, 0))) {
            w.duel().myAccepted(true);
            w.duel().otherAccepted(true);
            change.run();
            WindowsDto.Duel duel = BotLiveEncoder.windows(w.freezeTick()).duel();
            assertEquals(List.of(false, false), List.of(duel.accepted(), duel.theyAccepted()));
        }
    }
}
