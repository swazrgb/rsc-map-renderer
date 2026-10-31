package openrsc.bot.api.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import openrsc.gamedata.data.ItemId;
import org.junit.jupiter.api.Test;

class MutableBankTest {

    private static final int COINS = ItemId.COINS.id();
    private static final int LOGS = ItemId.LOGS.id();
    private static final int FEATHER = ItemId.FEATHER.id();

    @Test
    void aFullBankRefusesANewStackButStacksOntoAnOldOne() {
        MutableBank bank = new MutableBank();
        bank.setCapacity(2);
        bank.setAll(List.of(new BankSlot(0, COINS, 100), new BankSlot(1, LOGS, 20)));

        bank.added(FEATHER, 30);
        bank.added(LOGS, 5);

        assertEquals(
                List.of(new BankSlot(0, COINS, 100), new BankSlot(1, LOGS, 25)),
                bank.snapshot().slots());
    }

    @Test
    void aFullBankStillTakesAnOverflowStack() {
        MutableBank bank = new MutableBank();
        bank.setCapacity(1);
        bank.setAll(List.of(new BankSlot(0, LOGS, Integer.MAX_VALUE - 10)));

        bank.added(LOGS, 25);

        assertEquals(
                List.of(new BankSlot(0, LOGS, Integer.MAX_VALUE), new BankSlot(1, LOGS, 15)),
                bank.snapshot().slots());
    }
}
