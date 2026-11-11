package openrsc.sessionproxy.authentic;

import java.nio.BufferUnderflowException;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import openrsc.bot.core.login.PreLoginConfigs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates what an authentic client of version 235 sends into the packets a custom client sends for the same thing.
 * Both protocols number their client packets alike; what differs is the payload of about twenty of them, which the
 * server reads with {@code Payload235Parser} for the one and {@code PayloadCustomParser} for the other.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/parsers/impl/Payload235Parser.java">Payload235Parser</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/parsers/impl/PayloadCustomParser.java">PayloadCustomParser</a>
 */
final class ToServer {

    private static final Logger LOG = LoggerFactory.getLogger(ToServer.class);

    private static final int CAST_ON_INVENTORY_ITEM = 4;
    private static final int BANK_WITHDRAW = 22;
    private static final int BANK_DEPOSIT = 23;
    private static final int CHANGE_PASSWORD = 25;
    private static final int DUEL_OFFER_ITEM = 33;
    private static final int SLEEP_WORD = 45;
    private static final int COMMAND = 38;
    private static final int TRADE_OFFER_ITEMS = 46;
    private static final int CAST_ON_NPC = 50;
    private static final int GROUND_ITEM_USE_ITEM = 53;
    private static final int ITEM_COMMAND = 90;
    private static final int CAST_ON_SCENERY = 99;
    private static final int GAME_SETTINGS = 111;
    private static final int ADD_IGNORE = 132;
    private static final int CAST_ON_LAND = 158;
    private static final int REMOVE_FRIEND = 167;
    private static final int CAST_ON_BOUNDARY = 180;
    private static final int ADD_FRIEND = 195;
    private static final int REPORT_ABUSE = 206;
    private static final int SET_RECOVERY = 208;
    private static final int PRIVATE_MESSAGE = 218;
    private static final int PLAYER_CAST = 229;
    private static final int APPEARANCE = 235;
    private static final int REMOVE_IGNORE = 241;
    private static final int ITEM_DROP = 246;
    private static final int CAST_ON_GROUND_ITEM = 249;
    private static final int SET_DETAILS = 253;

    // The same opcode and payload in both protocols: heartbeat, walks, logouts, chat, interactions, shops, trades,
    // duels, prayers, privacy, banking's close, the tutorial skip and the recovery prompts.
    private static final Set<Integer> UNCHANGED = Set.of(
            8, 14, 16, 29, 31, 55, 59, 60, 64, 67, 77, 79, 84, 91, 102, 103, 104, 113, 115, 116, 127, 135, 136, 137,
            142, 153, 161, 165, 166, 169, 170, 171, 176, 187, 190, 196, 197, 202, 212, 216, 221, 230, 236, 247, 254);

    private final boolean bankNotes;
    private final IntUnaryOperator slotAmount;

    /**
     * @param server the configs of the server the session plays on
     * @param slotAmount how many of an item the client was last told an inventory slot holds
     */
    ToServer(PreLoginConfigs server, IntUnaryOperator slotAmount) {
        this.bankNotes = server.wantBankNotes();
        this.slotAmount = slotAmount;
    }

    /** The custom-protocol packet {@code body} stands for, or null for one the server is not sent. */
    byte @Nullable [] translate(byte[] body) {
        int opcode = body[0] & 0xFF;
        if (UNCHANGED.contains(opcode)) {
            return body;
        }
        PacketReader in = new PacketReader(body);
        PacketWriter out = new PacketWriter(opcode);
        try {
            switch (opcode) {
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/parsers/impl/PayloadCustomParser.java#L487-L500
                case APPEARANCE -> out.bytes(in.rest()).u8(0xFF).u8(0xFF);
                case ADD_IGNORE, ADD_FRIEND, REMOVE_FRIEND, REMOVE_IGNORE, COMMAND -> out.line(in.zeroQuoted());
                case PRIVATE_MESSAGE -> out.line(in.zeroQuoted()).bytes(in.rest());
                case REPORT_ABUSE -> out.line(in.zeroQuoted()).u8(in.u8()).u8(in.u8());
                // A 235 client sends a delay byte ahead of the word, which the server does not use.
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/parsers/impl/Payload235Parser.java#L697-L702
                case SLEEP_WORD -> {
                    in.u8();
                    out.line(in.zeroQuoted());
                }
                case DUEL_OFFER_ITEM, TRADE_OFFER_ITEMS -> {
                    int count = in.u8();
                    out.u8(count);
                    for (int i = 0; i < count; i++) {
                        out.u16(in.s16()).i32(in.i32()).u16(0);
                    }
                }
                // The custom protocol puts the spell first.
                case CAST_ON_NPC, PLAYER_CAST, CAST_ON_INVENTORY_ITEM -> {
                    int target = in.s16();
                    out.u16(in.s16()).u16(target);
                }
                case CAST_ON_LAND, CAST_ON_SCENERY -> {
                    int x = in.s16();
                    int y = in.s16();
                    out.u16(in.s16()).u16(x).u16(y);
                }
                case CAST_ON_GROUND_ITEM -> {
                    int x = in.s16();
                    int y = in.s16();
                    int item = in.s16();
                    out.u16(in.s16()).u16(x).u16(y).u16(item);
                }
                case CAST_ON_BOUNDARY -> {
                    int x = in.s16();
                    int y = in.s16();
                    int direction = in.u8();
                    out.u16(in.s16()).u16(x).u16(y).u8(direction);
                }
                case GROUND_ITEM_USE_ITEM -> {
                    int x = in.s16();
                    int y = in.s16();
                    int groundItem = in.s16();
                    out.u16(x).u16(y).u16(in.s16()).u16(groundItem);
                }
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L24-L26
                case ITEM_COMMAND -> out.u16(in.s16()).i32(1).u8(0);
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemDropHandler.java#L38-L43
                case ITEM_DROP -> {
                    int slot = in.s16();
                    out.u16(slot).i32(Math.max(1, slotAmount.applyAsInt(slot)));
                }
                case BANK_WITHDRAW -> {
                    out.u16(in.s16()).i32(in.i32());
                    if (bankNotes) {
                        out.u8(0);
                    }
                }
                case BANK_DEPOSIT -> out.u16(in.s16()).i32(in.i32());
                case GAME_SETTINGS -> {
                    int setting = settingIndex(in.u8());
                    if (setting < 0) {
                        return null;
                    }
                    out.u8(setting).u8(in.u8());
                }
                case CHANGE_PASSWORD, SET_RECOVERY, SET_DETAILS -> {
                    LOG.info("not passed on: an authentic client's change of password, recovery questions or details");
                    return null;
                }
                default -> {
                    LOG.debug("dropped authentic client packet {} ({} bytes)", opcode, body.length - 1);
                    return null;
                }
            }
        } catch (BufferUnderflowException e) {
            LOG.debug("dropped authentic client packet {}: it ends early", opcode);
            return null;
        }
        return out.toByteArray();
    }

    // A 235 client numbers the settings 0 camera, 1 PK mode, 2 mouse buttons, 3 sound; a custom client 0 camera,
    // 1 mouse buttons, 2 sound, and has no PK mode setting.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameSettingHandler.java#L101-L118
    private static int settingIndex(int authentic) {
        return switch (authentic) {
            case 0 -> 0;
            case 2 -> 1;
            case 3 -> 2;
            default -> -1;
        };
    }
}
