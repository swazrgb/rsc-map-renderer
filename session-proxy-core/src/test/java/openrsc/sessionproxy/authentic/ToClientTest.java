package openrsc.sessionproxy.authentic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.defs.ItemDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Each custom packet is built as the server's {@code PayloadCustomGenerator} writes it, and each expectation as its
 * {@code Payload235Generator} writes the same thing for an authentic client.
 */
class ToClientTest {

    private static final int COINS = 10;
    private static final int IRON_SHORT_SWORD = 1;
    private static final int SHANTAY_PASS = 1030;
    private static final int UNOBTANIUM = 1544;
    private static final int UNOBTANIUM_STACKABLE = 1545;
    private static final int CUSTOM_BANKS = 29;
    private static final int BANK_NOTES = 31;

    private static ItemDefs items;

    @BeforeAll
    static void loadItems() throws IOException {
        ServerConf conf = ServerConf.resolve();
        items = ItemDefs.load(conf.defs().resolve("ItemDefs.json"), conf.defs().resolve("ItemDefsCustom.json"));
    }

    @Test
    void aWithdrawalShowsTheInventoryBeforeTheBankAndNotTheWholeBankAgain() {
        ToClient translate = translator(Map.of(CUSTOM_BANKS, 1));
        translate.translate(bankOpen(COINS, 1000));

        assertEquals(
                List.of(),
                translate.translate(packet(249).u8(0).u16(COINS).i32(900).toByteArray()));
        assertEquals(List.of(), translate.translate(bankOpen(COINS, 900)));
        assertEquals(
                List.of("5a00000a0064", "f900000a0384"),
                hex(translate.translate(
                        packet(90).u8(0).u16(COINS).u8(0).i32(100).toByteArray())));
        assertEquals(
                List.of("3501000a0064"),
                hex(translate.translate(
                        packet(53).u8(1).u16(COINS).u8(0).u8(0).i32(100).toByteArray())));
        assertEquals(100, translate.slotAmount(0));
    }

    @Test
    void anEmptiedBankSlotNamesTheItemThatLeftIt() {
        ToClient translate = translator(Map.of());
        translate.translate(bankOpen(COINS, 1000));
        translate.translate(packet(249).u8(0).u16(0).i32(0).toByteArray());
        assertEquals(
                List.of("5a00000a03e8", "f900000a0000"),
                hex(translate.translate(
                        packet(90).u8(0).u16(COINS).u8(0).i32(1000).toByteArray())));
    }

    @Test
    void aBankShowsAtMost255ItemsAndAmountsPastAShortInFourBytes() {
        ToClient translate = translator(Map.of());
        PacketWriter open = packet(42).u16(300).u16(400);
        for (int i = 0; i < 300; i++) {
            open.u16(COINS).i32(100_000);
        }
        List<byte[]> out = translate.translate(open.toByteArray());
        byte[] bank = out.getLast();
        assertEquals("2affff000a800186a0", HexFormat.of().formatHex(bank, 0, 9));
        assertEquals(3 + 300 * 6, bank.length);
        assertEquals(
                List.of(
                        "Warning: Unable to display all items in bank!",
                        "Warning: Bank is unauthentically large. Deposited items may not be visible to be withdrawn!"),
                out.subList(0, out.size() - 1).stream()
                        .map(ToClientTest::messageText)
                        .toList(),
                "the warnings a free world's authentic client is sent about a bank too big for it");
    }

    @Test
    void anInventoryCarriesWieldedItemsInTheTopBitAndAmountsOnlyForStacks() {
        ToClient translate = translator(Map.of(BANK_NOTES, 1));
        byte[] inventory = packet(53)
                .u8(4)
                .u16(IRON_SHORT_SWORD)
                .u8(1)
                .u8(0)
                .u16(COINS)
                .u8(0)
                .u8(0)
                .i32(40_000)
                .u16(IRON_SHORT_SWORD)
                .u8(0)
                .u8(1)
                .i32(7)
                .u16(UNOBTANIUM)
                .u8(0)
                .u8(0)
                .toByteArray();
        assertEquals(List.of("35048001000a80009c40040600070000"), hex(translate.translate(inventory)));
        assertEquals(1, translate.slotAmount(0));
        assertEquals(40_000, translate.slotAmount(1));
        assertEquals(7, translate.slotAmount(2));
    }

    @Test
    void aNotedOfferReachesAnAuthenticClientAsPassesNamedInAMessage() {
        ToClient translate = translator(Map.of(BANK_NOTES, 1));
        byte[] offer = packet(97)
                .u8(1)
                .u16(IRON_SHORT_SWORD)
                .u8(1)
                .i32(5)
                .u8(1)
                .u16(COINS)
                .u8(0)
                .i32(3)
                .toByteArray();
        List<byte[]> out = translate.translate(offer);
        assertEquals(
                "@whi@Other player offered @gre@5 @yel@"
                        + items.get(IRON_SHORT_SWORD).name(),
                messageText(out.getFirst()));
        assertEquals(
                "6101" + String.format("%04x", SHANTAY_PASS) + "00000005",
                HexFormat.of().formatHex(out.getLast()));
    }

    @Test
    void aTradeOfItemsPastTheAuthenticOnesIsNotShownForConfirming() {
        ToClient translate = translator(Map.of());
        byte[] confirm = packet(20)
                .line("someone")
                .u8(1)
                .u16(UNOBTANIUM_STACKABLE)
                .i32(1)
                .u8(0)
                .toByteArray();
        assertEquals(List.of(), translate.translate(confirm));
    }

    @Test
    void aShopShowsOnlyTheAuthenticItems() {
        ToClient translate = translator(Map.of());
        byte[] shop = packet(101)
                .u8(2)
                .u8(1)
                .u8(130)
                .u8(40)
                .u8(3)
                .u16(UNOBTANIUM)
                .u16(1)
                .u16(1)
                .u16(COINS)
                .u16(5)
                .u16(5)
                .toByteArray();
        assertEquals(List.of("650101822803000a00050005"), hex(translate.translate(shop)));
    }

    @Test
    void anAuthenticClientIsToldWhichQuestsAreCompleteAndOnlyWhenOneCompletes() {
        ToClient translate = translator(Map.of());
        byte[] list = packet(5)
                .u8(0)
                .u8(2)
                .i32(0)
                .i32(-1)
                .line("Black knights' fortress")
                .i32(3)
                .i32(2)
                .line("Doric's quest")
                .toByteArray();
        byte[] complete = translate.translate(list).getFirst();
        assertEquals(51, complete.length);
        assertEquals(1, complete[1]);
        assertEquals(0, complete[4]);

        assertEquals(
                List.of(), translate.translate(packet(5).u8(1).i32(3).i32(4).toByteArray()));
        byte[] done = translate
                .translate(packet(5).u8(1).i32(3).i32(-1).toByteArray())
                .getFirst();
        assertEquals(1, done[4]);
    }

    @Test
    void statsKeepTheEighteenAuthenticSkills() {
        ToClient translate = translator(Map.of());
        PacketWriter stats = packet(156);
        for (int section = 0; section < 2; section++) {
            for (int skill = 0; skill < 20; skill++) {
                stats.u8(skill + 1);
            }
        }
        for (int skill = 0; skill < 20; skill++) {
            stats.i32(skill + 1);
        }
        byte[] out = translate.translate(stats.u8(9).toByteArray()).getFirst();
        assertEquals(1 + 18 + 18 + 18 * 4 + 1, out.length);
        assertEquals(18, out[18]);
        assertEquals(1, out[19]);
        assertEquals(18, out[out.length - 2]);
        assertEquals(9, out[out.length - 1]);
        assertEquals(
                List.of(),
                translate.translate(packet(159).u8(18).u8(1).u8(1).i32(0).toByteArray()),
                "a skill an authentic client has not");
    }

    @Test
    void aMenuShowsItsFirstFiveOptionsAndNamesTheRest() {
        ToClient translate = translator(Map.of());
        PacketWriter menu = packet(245).u8(6);
        for (int i = 1; i <= 6; i++) {
            menu.line("option " + i);
        }
        List<byte[]> out = translate.translate(menu.toByteArray());
        assertEquals(
                List.of(
                        "@red@There is a bug in the server which prevented you from seeing all options.",
                        "@ran@Please report this! @whi@You are missing these options:",
                        "@ora@6) @whi@option 6"),
                out.subList(0, 3).stream().map(ToClientTest::messageText).toList());
        byte[] shown = out.getLast();
        assertEquals(5, shown[1]);
        assertEquals(
                "\0option 1\0\0option 2\0\0option 3\0\0option 4\0\0option 5\0",
                new String(shown, 2, shown.length - 2, StandardCharsets.ISO_8859_1));
    }

    @Test
    void anInputBoxIsDescribedInMessages() {
        ToClient translate = translator(Map.of());
        List<byte[]> out = translate.translate(packet(110).line("How many?").toByteArray());
        assertEquals(
                List.of(
                        "@lre@Input Box not implemented for authentic clients.",
                        "@lre@Server asked the following: @whi@How many?"),
                out.stream().map(ToClientTest::messageText).toList());
    }

    @Test
    void anInvisiblePlayerWearsTheOutfitThatShowsIt() {
        ToClient translate = translator(Map.of());
        translate.translate(packet(25).u16(7).u16(0).u16(0).u16(0).u16(0).toByteArray());
        PacketWriter look = packet(234).u16(1).u16(7).u8(5).line("ghost").u8(12);
        for (int sprite : new int[] {1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0}) {
            look.u16(sprite);
        }
        look.u8(2).u8(8).u8(14).u8(0).u8(3).u8(0);
        look.u8(0).u8(1).u8(0).u8(0).i32(0);
        byte[] out = translate.translate(look.toByteArray()).getFirst();
        assertEquals(
                "ea000100070500000067686f7374000067686f7374000b000000000000000000e30002080e000300",
                HexFormat.of().formatHex(out));
    }

    @Test
    void welcomeCarriesTheAddressInFourBytes() {
        ToClient translate = translator(Map.of());
        byte[] v4 = packet(182).line("10.1.2.3").u16(4).u16(0).toByteArray();
        assertEquals(List.of("b60a0102030004c80001"), hex(translate.translate(v4)));
        byte[] v6 = packet(182).line("2001:db8::1:ab").u16(4).u16(10).toByteArray();
        assertEquals(List.of("b6060001ab0004040001"), hex(translate.translate(v6)));
    }

    @Test
    void anItemLeavingTheInventoryMovesTheLaterAmountsUp() {
        ToClient translate = translator(Map.of());
        translate.translate(packet(53)
                .u8(2)
                .u16(IRON_SHORT_SWORD)
                .u8(0)
                .u8(0)
                .u16(COINS)
                .u8(0)
                .u8(0)
                .i32(5000)
                .toByteArray());
        assertEquals(List.of("7b00"), hex(translate.translate(packet(123).u8(0).toByteArray())));
        assertEquals(5000, translate.slotAmount(0));
        assertEquals(0, translate.slotAmount(1));
    }

    @Test
    void groundItemsPastTheAuthenticOnesAreNotShown() {
        ToClient translate = translator(Map.of());
        byte[] ground = packet(99)
                .u16(UNOBTANIUM_STACKABLE)
                .u8(1)
                .u8(2)
                .u16(COINS)
                .u8(3)
                .u8(4)
                .u16(0x8000 | UNOBTANIUM)
                .u8(5)
                .u8(6)
                .u8(0xFF)
                .u8(7)
                .u8(8)
                .toByteArray();
        assertEquals(List.of("63000a0304ff0708"), hex(translate.translate(ground)));
    }

    @Test
    void anEmptiedBankSlotMovesTheLaterSlotsUp() {
        ToClient translate = translator(Map.of());
        translate.translate(packet(42)
                .u16(2)
                .u16(192)
                .u16(COINS)
                .i32(10)
                .u16(IRON_SHORT_SWORD)
                .i32(1)
                .toByteArray());
        translate.translate(packet(249).u8(0).u16(0).i32(0).toByteArray());
        translate.translate(packet(249).u8(0).u16(0).i32(0).toByteArray());
        assertEquals(
                List.of("f900000a0000", "f90000010000"),
                hex(translate.translate(packet(131).i32(0).u8(0).u8(0).line("x").toByteArray()))
                        .subList(0, 2));
    }

    @Test
    void globalChatComesFromGlobalWithTheSpeakerInTheText() {
        ToClient translate = translator(Map.of());
        byte[] chat = packet(120)
                .line("Global$someone")
                .line("Global$someone")
                .i32(0)
                .chat("hi all")
                .toByteArray();
        byte[] out = translate.translate(chat).getFirst();
        String expected = "@ora@[@gre@someone@ora@]:@cya@ hi all";
        byte[] message = new PacketWriter(-1).chat(expected).toByteArray();
        assertEquals(
                HexFormat.of()
                        .formatHex(new PacketWriter(120)
                                .zeroQuoted("Global$")
                                .zeroQuoted("Global$")
                                .u8(0)
                                .u8(0)
                                .u8(0)
                                .u8(0)
                                .u16(1)
                                .u8(0)
                                .u8(0)
                                .u8(1)
                                .bytes(java.util.Arrays.copyOfRange(message, 1, message.length))
                                .toByteArray()),
                HexFormat.of().formatHex(out));
    }

    private static ToClient translator(Map<Integer, Integer> flags) {
        return new ToClient(items, new PreLoginConfigs("test", BigInteger.ONE, BigInteger.ONE, flags));
    }

    private static byte[] bankOpen(int id, int amount) {
        return packet(42).u16(1).u16(192).u16(id).i32(amount).toByteArray();
    }

    private static PacketWriter packet(int opcode) {
        return new PacketWriter(opcode);
    }

    // A game message's text, between the zero bytes after its type and flags.
    private static String messageText(byte[] message) {
        assertEquals(131, message[0] & 0xFF);
        assertTrue(message.length >= 5);
        return new String(message, 4, message.length - 5, StandardCharsets.ISO_8859_1);
    }

    private static List<String> hex(List<byte[]> packets) {
        return packets.stream().map(p -> HexFormat.of().formatHex(p)).toList();
    }
}
