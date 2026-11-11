package openrsc.bot.core.world;

import static openrsc.bot.core.world.OpcodeGate.AUTHENTIC;
import static openrsc.bot.core.world.OpcodeGate.BY_PAYLOAD;
import static openrsc.bot.core.world.OpcodeGate.needs;

import java.util.EnumSet;
import java.util.Set;
import openrsc.bot.api.Extension;
import org.jetbrains.annotations.Nullable;

public enum Opcode {
    SEND_LIVE_TICK(Dir.IN, 251),
    SEND_TICK_END(Dir.IN, 248),
    SEND_PLAYER_COORDS(Dir.IN, 191),
    SEND_NPC_COORDS(Dir.IN, 79),
    SEND_LOAD_AREA(Dir.IN, 25),
    SEND_SCENERY(Dir.IN, 48),
    SEND_WALL_OBJECTS(Dir.IN, 91),
    SEND_SERVER_MESSAGE(Dir.IN, 131),
    SEND_LOGOUT(Dir.IN, 165),
    SEND_DEATH(Dir.IN, 83),
    SEND_FATIGUE(Dir.IN, 114),
    SEND_STATS(Dir.IN, 156),
    SEND_STAT(Dir.IN, 159),
    SEND_EXPERIENCE(Dir.IN, 33),
    SEND_INVENTORY(Dir.IN, 53),
    SEND_INVENTORY_UPDATEITEM(Dir.IN, 90),
    SEND_INVENTORY_REMOVEITEM(Dir.IN, 123),
    SEND_WELCOME_INFO(Dir.IN, 182),
    SEND_PRIVACY_SETTINGS(Dir.IN, 51),
    SEND_GAME_SETTINGS(Dir.IN, 240),
    SEND_WORLD_INFO(Dir.IN, 25),
    SEND_QUESTS(Dir.IN, 5),
    SEND_APPEARANCE_SCREEN(Dir.IN, 59),
    SEND_ON_TUTORIAL(Dir.IN, 111),
    SEND_SLEEPSCREEN(Dir.IN, 117),
    SEND_STOPSLEEP(Dir.IN, 84),
    SEND_SLEEP_FATIGUE(Dir.IN, 244),
    SEND_SLEEPWORD_INCORRECT(Dir.IN, 194),
    SEND_OPTION_MENU_OPEN(Dir.IN, 245),
    SEND_OPTION_MENU_CLOSE(Dir.IN, 252),
    SEND_BOX(Dir.IN, 222),
    SEND_BOX2(Dir.IN, 89),
    SEND_UPDATE_NPC(Dir.IN, 104),
    SEND_UPDATE_PLAYERS(Dir.IN, 234),
    SEND_GROUND_ITEMS(Dir.IN, 99),
    SEND_BANK_OPEN(Dir.IN, 42),
    SEND_BANK_CLOSE(Dir.IN, 203),
    SEND_BANK_UPDATE(Dir.IN, 249),
    SEND_SHOP_OPEN(Dir.IN, 101),
    SEND_SHOP_CLOSE(Dir.IN, 137),
    SEND_REMOVE_WORLD_ENTITY(Dir.IN, 211),
    SEND_NPC_KILLS(Dir.IN, 147),

    SEND_EQUIPMENT(Dir.IN, 254),
    SEND_EQUIPMENT_UPDATE(Dir.IN, 255),
    SEND_EQUIPMENT_STATS(Dir.IN, 153),
    SEND_PRAYERS_ACTIVE(Dir.IN, 206),
    SEND_COMBAT_STYLE(Dir.IN, 129),
    SEND_BUBBLE(Dir.IN, 36),
    SEND_PLAY_SOUND(Dir.IN, 204),

    SEND_SYSTEM_UPDATE(Dir.IN, 52),
    SEND_ELIXIR(Dir.IN, 54),
    SEND_EXPERIENCE_TOGGLE(Dir.IN, 34),
    SEND_EXPSHARED(Dir.IN, 98),
    SEND_APPEARANCE_KEEPALIVE(Dir.IN, 213),
    SEND_INPUT_BOX(Dir.IN, 110),
    SEND_BANK_PIN_INTERFACE(Dir.IN, 135),
    SEND_STATUS_PROGRESS_BAR(Dir.IN, 134),

    SEND_DUEL_WINDOW(Dir.IN, 176),
    SEND_DUEL_OPPONENTS_ITEMS(Dir.IN, 6),
    SEND_DUEL_SETTINGS(Dir.IN, 30),
    SEND_DUEL_ACCEPTED(Dir.IN, 210),
    SEND_DUEL_OTHER_ACCEPTED(Dir.IN, 253),
    SEND_DUEL_CONFIRMWINDOW(Dir.IN, 172),
    SEND_DUEL_CLOSE(Dir.IN, 225),

    SEND_CLAN(Dir.IN, 112),
    SEND_PARTY(Dir.IN, 116),

    SEND_KILL_ANNOUNCEMENT(Dir.IN, 118),
    SEND_ON_BLACK_HOLE(Dir.IN, 115),
    SEND_IRONMAN(Dir.IN, 113),
    SEND_FISHING_TRAWLER(Dir.IN, 133),
    SEND_AUCTION_LIST(Dir.IN, 132),
    SEND_ONLINE_LIST(Dir.IN, 136),
    SEND_OPENPK_POINTS(Dir.IN, 148),
    SEND_OPENPK_POINTS_TO_GP_RATIO(Dir.IN, 144),
    SEND_UPDATE_IGNORE_LIST_BECAUSE_NAME_CHANGE(Dir.IN, 237),
    SEND_UNLOCKED_APPEARANCES(Dir.IN, 250),
    SEND_BANK_PRESET(Dir.IN, 150),

    SEND_LOGOUT_REQUEST_CONFIRM(Dir.IN, 4),
    SEND_CANT_LOGOUT(Dir.IN, 183),
    SEND_OPEN_RECOVERY(Dir.IN, 224),
    SEND_OPEN_DETAILS(Dir.IN, 232),
    SEND_28_BYTES_UNUSED(Dir.IN, 189),
    SEND_SERVER_CONFIGS(Dir.IN, 19),

    SEND_FRIEND_UPDATE(Dir.IN, 149),
    SEND_IGNORE_LIST(Dir.IN, 109),
    SEND_PRIVATE_MESSAGE(Dir.IN, 120),
    SEND_PRIVATE_MESSAGE_SENT(Dir.IN, 87),

    SEND_TRADE_WINDOW(Dir.IN, 92),
    SEND_TRADE_OTHER_ITEMS(Dir.IN, 97),
    SEND_TRADE_ACCEPTED(Dir.IN, 15),
    SEND_TRADE_OTHER_ACCEPTED(Dir.IN, 162),
    SEND_TRADE_OPEN_CONFIRM(Dir.IN, 20),
    SEND_TRADE_CLOSE(Dir.IN, 128),

    OPCODE_CAST_ON_SELF(Dir.OUT, 137, AUTHENTIC),
    OPCODE_ITEM_COMMAND(Dir.OUT, 90, BY_PAYLOAD),
    OPCODE_SLEEPWORD(Dir.OUT, 45, AUTHENTIC),
    OPCODE_SHOP_BUY(Dir.OUT, 236, AUTHENTIC),
    OPCODE_SHOP_SELL(Dir.OUT, 221, AUTHENTIC),
    OPCODE_SHOP_CLOSE(Dir.OUT, 166, AUTHENTIC),

    OPCODE_WALK_TO_POINT(Dir.OUT, 187, AUTHENTIC),
    OPCODE_WALK_TO_ENTITY(Dir.OUT, 16, AUTHENTIC),

    OPCODE_AT_OBJECT(Dir.OUT, 136, AUTHENTIC),
    OPCODE_AT_OBJECT2(Dir.OUT, 79, AUTHENTIC),

    OPCODE_LOGOUT(Dir.OUT, 102, AUTHENTIC),
    OPCODE_HEARTBEAT(Dir.OUT, 67, AUTHENTIC),
    OPCODE_APPEARANCE_UPDATE(Dir.OUT, 235, AUTHENTIC),
    OPCODE_SKIP_TUTORIAL(Dir.OUT, 84, AUTHENTIC),
    OPCODE_AT_WALL_OBJECT(Dir.OUT, 14, AUTHENTIC),
    OPCODE_AT_WALL_OBJECT2(Dir.OUT, 127, AUTHENTIC),
    OPCODE_DEPOSIT(Dir.OUT, 23, AUTHENTIC),
    OPCODE_WITHDRAW(Dir.OUT, 22, BY_PAYLOAD),
    OPCODE_BANK_CLOSE(Dir.OUT, 212, AUTHENTIC),
    OPCODE_DROP(Dir.OUT, 246, AUTHENTIC),
    OPCODE_TALK_TO_NPC(Dir.OUT, 153, AUTHENTIC),
    OPCODE_DIALOGUE_REPLY(Dir.OUT, 116, AUTHENTIC),
    OPCODE_ATTACK_NPC(Dir.OUT, 190, AUTHENTIC),
    OPCODE_ATTACK_PLAYER(Dir.OUT, 171, AUTHENTIC),
    OPCODE_COMBAT_STYLE(Dir.OUT, 29, AUTHENTIC),
    OPCODE_PRAYER_ACTIVATED(Dir.OUT, 60, AUTHENTIC),
    OPCODE_PRAYER_DEACTIVATED(Dir.OUT, 254, AUTHENTIC),
    OPCODE_NPC_COMMAND1(Dir.OUT, 202, BY_PAYLOAD),
    OPCODE_NPC_COMMAND2(Dir.OUT, 203, BY_PAYLOAD),
    OPCODE_ITEM_EQUIP(Dir.OUT, 169, AUTHENTIC),
    OPCODE_ITEM_UNEQUIP(Dir.OUT, 170, AUTHENTIC),
    OPCODE_PLAYER_FOLLOW(Dir.OUT, 165, AUTHENTIC),
    OPCODE_PLAYER_DUEL(Dir.OUT, 103, AUTHENTIC),
    OPCODE_USE_ITEM_ON_OBJECT(Dir.OUT, 115, BY_PAYLOAD),
    OPCODE_USE_ITEM_ON_BOUNDARY(Dir.OUT, 161, BY_PAYLOAD),
    OPCODE_CAST_ON_SCENERY(Dir.OUT, 99, AUTHENTIC),
    OPCODE_CAST_ON_NPC(Dir.OUT, 50, AUTHENTIC),
    OPCODE_CAST_ON_PLAYER(Dir.OUT, 229, AUTHENTIC),
    OPCODE_CAST_ON_GROUND_ITEM(Dir.OUT, 249, AUTHENTIC),
    OPCODE_CAST_ON_INVENTORY_ITEM(Dir.OUT, 4, AUTHENTIC),
    OPCODE_PICKUP_GROUND_ITEM(Dir.OUT, 247, AUTHENTIC),
    OPCODE_USE_ITEM_ON_GROUND_ITEM(Dir.OUT, 53, AUTHENTIC),
    OPCODE_USE_ITEM_ON_ITEM(Dir.OUT, 91, AUTHENTIC),
    OPCODE_USE_ITEM_ON_NPC(Dir.OUT, 135, BY_PAYLOAD),
    OPCODE_USE_ITEM_ON_PLAYER(Dir.OUT, 113, AUTHENTIC),
    OPCODE_TRADE_REQUEST(Dir.OUT, 142, AUTHENTIC),
    OPCODE_TRADE_OFFER(Dir.OUT, 46, BY_PAYLOAD),
    OPCODE_TRADE_ACCEPT_FIRST(Dir.OUT, 55, AUTHENTIC),
    OPCODE_TRADE_CONFIRM(Dir.OUT, 104, AUTHENTIC),
    OPCODE_TRADE_DECLINE(Dir.OUT, 230, AUTHENTIC),
    OPCODE_DUEL_OFFER(Dir.OUT, 33, BY_PAYLOAD),
    OPCODE_DUEL_RULES(Dir.OUT, 8, AUTHENTIC),
    OPCODE_DUEL_ACCEPT_FIRST(Dir.OUT, 176, AUTHENTIC),
    OPCODE_DUEL_CONFIRM(Dir.OUT, 77, AUTHENTIC),
    OPCODE_DUEL_DECLINE(Dir.OUT, 197, AUTHENTIC),

    OPCODE_COMMAND(Dir.OUT, 38, BY_PAYLOAD),
    OPCODE_CHAT(Dir.OUT, 216, AUTHENTIC),
    OPCODE_SEND_PRIVATE_MESSAGE(Dir.OUT, 218, AUTHENTIC),
    OPCODE_ADD_FRIEND(Dir.OUT, 195, AUTHENTIC),
    OPCODE_REMOVE_FRIEND(Dir.OUT, 167, AUTHENTIC),

    OPCODE_GAME_SETTINGS_CHANGED(Dir.OUT, 111, AUTHENTIC),
    OPCODE_PRIVACY_SETTINGS_CHANGED(Dir.OUT, 64, AUTHENTIC),

    OPCODE_ITEM_UNEQUIP_FROM_EQUIPMENT(Dir.OUT, 168, needs(Extension.EQUIPMENT_TAB)),
    OPCODE_ITEM_EQUIP_FROM_BANK(Dir.OUT, 172, needs(Extension.EQUIPMENT_TAB)),
    OPCODE_ITEM_REMOVE_TO_BANK(Dir.OUT, 173, needs(Extension.EQUIPMENT_TAB)),
    OPCODE_BANK_DEPOSIT_ALL_FROM_INVENTORY(Dir.OUT, 24, needs(Extension.CUSTOM_BANKS)),
    OPCODE_BANK_DEPOSIT_ALL_FROM_EQUIPMENT(Dir.OUT, 26, needs(Extension.EQUIPMENT_TAB, Extension.CUSTOM_BANKS)),
    OPCODE_BANK_SAVE_PRESET(
            Dir.OUT, 27, needs(Extension.EQUIPMENT_TAB, Extension.BANK_PRESETS, Extension.CUSTOM_BANKS)),
    OPCODE_BANK_LOAD_PRESET(
            Dir.OUT, 28, needs(Extension.EQUIPMENT_TAB, Extension.BANK_PRESETS, Extension.CUSTOM_BANKS)),
    OPCODE_INTERFACE_OPTION(Dir.OUT, 199, BY_PAYLOAD);

    public enum Dir {
        IN,
        OUT
    }

    private final Dir dir;
    private final int wire;

    @SuppressWarnings("ImmutableEnumChecker")
    private final @Nullable OpcodeGate gate;

    Opcode(Dir dir, int wire) {
        this(dir, wire, null);
    }

    Opcode(Dir dir, int wire, @Nullable OpcodeGate gate) {
        if ((dir == Dir.OUT) != (gate != null)) {
            throw new IllegalArgumentException(
                    name() + ": an outbound opcode declares its OpcodeGate, an inbound one none");
        }
        this.dir = dir;
        this.wire = wire;
        this.gate = gate;
    }

    public int wire() {
        return wire;
    }

    public Dir dir() {
        return dir;
    }

    public OpcodeGate gate() {
        if (gate == null) {
            throw new IllegalStateException(this + " is inbound: only what we send is gated");
        }
        return gate;
    }

    private static final Opcode[] IN_BY_WIRE = new Opcode[256];
    private static final Opcode[] OUT_BY_WIRE = new Opcode[256];

    static {
        for (Opcode o : values()) {
            Opcode[] table = o.dir == Dir.IN ? IN_BY_WIRE : OUT_BY_WIRE;
            if (table[o.wire] == null) {
                table[o.wire] = o;
            }
        }
    }

    public static @Nullable Opcode inbound(int wire) {
        return wire >= 0 && wire < IN_BY_WIRE.length ? IN_BY_WIRE[wire] : null;
    }

    public static @Nullable Opcode outbound(int wire) {
        return wire >= 0 && wire < OUT_BY_WIRE.length ? OUT_BY_WIRE[wire] : null;
    }

    private static final Set<Opcode> MENU_SAFE = EnumSet.of(
            OPCODE_DIALOGUE_REPLY,
            OPCODE_HEARTBEAT,
            OPCODE_CHAT,
            OPCODE_SEND_PRIVATE_MESSAGE,
            OPCODE_COMBAT_STYLE,
            OPCODE_GAME_SETTINGS_CHANGED,
            OPCODE_PRIVACY_SETTINGS_CHANGED);

    public boolean cancelsOptionMenu() {
        return !MENU_SAFE.contains(this);
    }

    public static boolean cancelsOptionMenu(int wire) {
        Opcode op = outbound(wire);
        return op == null || op.cancelsOptionMenu();
    }
}
