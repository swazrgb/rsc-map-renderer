package openrsc.sessionproxy.authentic;

import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import openrsc.bot.core.codec.Huffman;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates the server's custom-protocol packets into what it sends an authentic client of version 235 instead.
 * Both protocols number their server packets alike. Where the payloads differ, the server's two generators,
 * {@code PayloadCustomGenerator} and {@code Payload235Generator}, and the places it builds a packet differently for an
 * authentic client, are what this follows. A session a custom client opened is also sent items past the authentic
 * ones, which are shown as the server shows them to an authentic client: as stand-ins, or not at all.
 *
 * <p>Keeps the little state the translation needs: the quest stages, the bank's slots, the inventory's amounts, and
 * the player's own server index. Not thread-safe, but for {@link #slotAmount}: one wire translates one packet at
 * a time.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java">Payload235Generator</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java">PayloadCustomGenerator</a>
 */
final class ToClient {

    private static final Logger LOG = LoggerFactory.getLogger(ToClient.class);

    private static final int QUESTS = 5;
    private static final int DUEL_OPPONENT_ITEMS = 6;
    private static final int TRADE_CONFIRM = 20;
    private static final int WORLD_INFO = 25;
    private static final int BANK_OPEN = 42;
    private static final int SCENERY = 48;
    private static final int PRIVACY_SETTINGS = 51;
    private static final int INVENTORY = 53;
    private static final int NPC_COORDS = 79;
    private static final int PRIVATE_MESSAGE_SENT = 87;
    private static final int BOX2 = 89;
    private static final int INVENTORY_ITEM = 90;
    private static final int INVENTORY_REMOVE = 123;
    private static final int BOUNDARIES = 91;
    private static final int TRADE_OTHER_ITEMS = 97;
    private static final int GROUND_ITEMS = 99;
    private static final int SHOP_OPEN = 101;
    private static final int UPDATE_NPCS = 104;
    private static final int IGNORE_LIST = 109;
    private static final int INPUT_BOX = 110;
    private static final int FATIGUE = 114;
    private static final int SLEEP_SCREEN = 117;
    private static final int PRIVATE_MESSAGE = 120;
    private static final int SERVER_MESSAGE = 131;
    private static final int ONLINE_LIST = 136;
    private static final int FRIEND_UPDATE = 149;
    private static final int EQUIPMENT_STATS = 153;
    private static final int STATS = 156;
    private static final int STAT = 159;
    private static final int DUEL_CONFIRM = 172;
    private static final int WELCOME = 182;
    private static final int PLAYER_COORDS = 191;
    private static final int PLAY_SOUND = 204;
    private static final int BOX = 222;
    private static final int UPDATE_PLAYERS = 234;
    private static final int IGNORE_RENAME = 237;
    private static final int GAME_SETTINGS = 240;
    private static final int SLEEP_FATIGUE = 244;
    private static final int OPTIONS_MENU = 245;
    private static final int BANK_UPDATE = 249;

    // The same payload in both protocols.
    private static final Set<Integer> UNCHANGED = Set.of(
            4, 15, 30, 33, 36, 52, 59, 83, 84, 92, 111, 128, 137, 162, 165, 176, 183, 189, 194, 203, 206, 210, 211, 213,
            224, 225, 232, 252, 253);

    // Only a custom client is sent these: configs, experience toggles, elixirs, shared experience, clans, ironman,
    // the black hole, parties, kill announcements, combat style, auctions, the trawler, progress bars, bank pins,
    // OpenPK points, NPC kill counts, bank presets, unlocked appearances and the equipment tab.
    private static final Set<Integer> CUSTOM_ONLY =
            Set.of(19, 34, 54, 98, 112, 113, 115, 116, 118, 129, 132, 133, 134, 135, 144, 147, 148, 150, 250, 254, 255);

    private static final int MESSAGE_GAME = 0;
    private static final int MESSAGE_QUEST = 3;
    private static final int AUTHENTIC_SKILLS = 18;
    private static final int AUTHENTIC_QUESTS = 50;
    private static final int MAX_AUTHENTIC_ITEM = 1289;
    private static final int SHANTAY_PASS = 1030;
    private static final int BRONZE_ARROWS = 11;
    private static final int IRON_MACE = 0;
    private static final int REMOVED_LOCATION = 60000;
    private static final int MAX_BOX_LENGTH = 4975;
    private static final int MAX_MENU_OPTIONS = 5;
    private static final String GLOBAL = "Global$";

    private final ItemDefs items;
    private final boolean bankNotes;
    private final boolean customBanks;
    private final boolean members;
    private final Map<Integer, Integer> questStages = new HashMap<>();
    private final List<Integer> bankSlots = new ArrayList<>();
    private final AtomicIntegerArray inventoryAmounts = new AtomicIntegerArray(30);
    private final List<byte[]> heldBankUpdates = new ArrayList<>();
    private int selfIndex = -1;
    private int ownAppearances;
    private int privateMessages;
    private boolean bankUpdateSent;

    ToClient(ItemDefs items, PreLoginConfigs server) {
        this.items = items;
        this.bankNotes = server.wantBankNotes();
        this.customBanks = server.flags().getOrDefault(29, 0) != 0;
        this.members = server.flags().getOrDefault(43, 0) != 0;
    }

    /**
     * How many of an item the client was last told an inventory slot holds; 0 for an empty slot. Safe to call from
     * any thread.
     */
    int slotAmount(int slot) {
        return slot >= 0 && slot < inventoryAmounts.length() ? inventoryAmounts.get(slot) : 0;
    }

    private void setSlotAmount(int slot, int amount) {
        if (slot < inventoryAmounts.length()) {
            inventoryAmounts.set(slot, amount);
        }
    }

    /** The 235 packets {@code body} stands for; none for a packet an authentic client is not sent. */
    List<byte[]> translate(byte[] body) {
        int opcode = body[0] & 0xFF;
        boolean afterBankUpdate = bankUpdateSent;
        bankUpdateSent = opcode == BANK_UPDATE;
        // A custom client of a world with custom banks is sent the whole bank again after each slot changes.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L2007-L2010
        if (opcode == BANK_OPEN && customBanks && afterBankUpdate) {
            return List.of();
        }
        List<byte[]> out = new ArrayList<>(2);
        // A withdrawal tells a custom client the bank's slot before the inventory's; an authentic one needs the
        // inventory first.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L509-L528
        if (opcode != INVENTORY && opcode != INVENTORY_ITEM && opcode != BANK_UPDATE) {
            out.addAll(heldBankUpdates);
            heldBankUpdates.clear();
        }
        try {
            translate(opcode, body, out);
        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
            LOG.debug("dropped server packet {} for an authentic client: it ends early", opcode);
        }
        if (opcode == INVENTORY || opcode == INVENTORY_ITEM) {
            out.addAll(heldBankUpdates);
            heldBankUpdates.clear();
        }
        return out;
    }

    private void translate(int opcode, byte[] body, List<byte[]> out) {
        if (UNCHANGED.contains(opcode)) {
            out.add(body);
            return;
        }
        if (CUSTOM_ONLY.contains(opcode)) {
            return;
        }
        PacketReader in = new PacketReader(body);
        switch (opcode) {
            case WORLD_INFO -> {
                selfIndex = new PacketReader(body).u16();
                out.add(body);
            }
            case QUESTS -> quests(in, out);
            case PLAYER_COORDS -> out.add(positions(body, 11, 0));
            case NPC_COORDS -> out.add(positions(body, 12, 10));
            case UPDATE_PLAYERS -> out.add(players(in));
            case UPDATE_NPCS -> npcs(in, out);
            case SCENERY -> out.add(locations(in, opcode, false));
            case BOUNDARIES -> {
                byte[] kept = locations(in, opcode, true);
                if (kept.length > 1) {
                    out.add(kept);
                }
            }
            case GROUND_ITEMS -> out.add(groundItems(in));
            case INVENTORY -> out.add(inventory(in));
            case INVENTORY_ITEM -> out.add(inventoryItem(body, in));
            // The server takes the item out of a list: the later items move up a slot.
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L271-L276
            case INVENTORY_REMOVE -> {
                for (int slot = in.u8(); slot < inventoryAmounts.length(); slot++) {
                    setSlotAmount(slot, slot + 1 < inventoryAmounts.length() ? inventoryAmounts.get(slot + 1) : 0);
                }
                out.add(body);
            }
            case BANK_OPEN -> bankOpen(in, out);
            case BANK_UPDATE -> heldBankUpdates.add(bankUpdate(in));
            case SHOP_OPEN -> out.add(shop(in));
            case TRADE_OTHER_ITEMS -> tradeOtherItems(in, out);
            case TRADE_CONFIRM -> tradeConfirm(in, out);
            case DUEL_OPPONENT_ITEMS -> duelOpponentItems(in, out);
            case DUEL_CONFIRM -> duelConfirm(in, out);
            case STATS -> out.add(stats(body));
            case STAT -> {
                if ((body[1] & 0xFF) < AUTHENTIC_SKILLS) {
                    out.add(body);
                }
            }
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L320-L332
            case EQUIPMENT_STATS -> out.add(Arrays.copyOf(body, 6));
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L365-L370
            case FATIGUE, SLEEP_FATIGUE -> {
                in.u16();
                out.add(new PacketWriter(opcode).u16(in.u16()).toByteArray());
            }
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L385-L393
            case GAME_SETTINGS -> out.add(Arrays.copyOf(body, 4));
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L121-L128
            case PRIVACY_SETTINGS -> {
                PacketWriter w = new PacketWriter(opcode);
                for (int i = 0; i < 4; i++) {
                    w.u8(in.u8() != 0 ? 1 : 0);
                }
                out.add(w.toByteArray());
            }
            case SERVER_MESSAGE -> out.add(serverMessage(in));
            case PRIVATE_MESSAGE -> out.add(privateMessage(in));
            case PRIVATE_MESSAGE_SENT ->
                out.add(new PacketWriter(opcode)
                        .zeroQuoted(in.line())
                        .bytes(in.rest())
                        .toByteArray());
            case BOX, BOX2 ->
                out.add(new PacketWriter(opcode).zeroQuoted(box(in.line())).toByteArray());
            case OPTIONS_MENU -> optionsMenu(in, out);
            case FRIEND_UPDATE -> {
                PacketWriter w = new PacketWriter(opcode)
                        .zeroQuoted(in.line())
                        .zeroQuoted(in.line())
                        .u8(in.u8());
                if (in.remaining() > 0) {
                    w.zeroQuoted(in.line());
                }
                out.add(w.toByteArray());
            }
            case IGNORE_LIST -> {
                int count = in.u8();
                PacketWriter w = new PacketWriter(opcode).u8(count);
                for (int i = 0; i < count * 4; i++) {
                    w.zeroQuoted(in.line());
                }
                out.add(w.toByteArray());
            }
            case IGNORE_RENAME ->
                out.add(new PacketWriter(opcode)
                        .zeroQuoted(in.line())
                        .zeroQuoted(in.line())
                        .zeroQuoted(in.line())
                        .zeroQuoted(in.line())
                        .u8(in.u8())
                        .toByteArray());
            case PLAY_SOUND ->
                out.add(new PacketWriter(opcode).zeroQuoted(in.line()).toByteArray());
            case WELCOME -> out.add(welcome(in));
            case SLEEP_SCREEN -> out.add(SleepImage.toAuthentic(body));
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L2055-L2060
            case INPUT_BOX -> {
                String prompt = in.line();
                out.add(message(MESSAGE_GAME, "@lre@Input Box not implemented for authentic clients."));
                out.add(message(MESSAGE_GAME, "@lre@Server asked the following: @whi@" + prompt));
            }
            case ONLINE_LIST -> out.add(onlineList(in));
            default -> LOG.debug("dropped server packet {} for an authentic client: it has no translation", opcode);
        }
    }

    // A full list, or one quest's stage; an authentic client is sent the list of which quests are complete, and
    // only when a quest completes.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1183-L1198
    private void quests(PacketReader in, List<byte[]> out) {
        if (in.u8() == 1) {
            int id = in.i32();
            int stage = in.i32();
            questStages.put(id, stage);
            if (stage < 0) {
                out.add(questList());
            }
            return;
        }
        int count = in.u8();
        for (int i = 0; i < count; i++) {
            int id = in.i32();
            questStages.put(id, in.i32());
            in.line();
        }
        out.add(questList());
    }

    private byte[] questList() {
        PacketWriter w = new PacketWriter(QUESTS);
        for (int id = 0; id < AUTHENTIC_QUESTS; id++) {
            w.u8(questStages.getOrDefault(id, 0) < 0 ? 1 : 0);
        }
        return w.toByteArray();
    }

    // The known mobs' codes are the same; a new mob's offsets are 6 bits for a custom client and 5 for an authentic
    // one, which the 5 bits hold while the view reaches no further than 15 tiles.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L266-L278
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L388-L450
    private static byte[] positions(byte[] body, int indexBits, int idBits) {
        int opcode = body[0] & 0xFF;
        Bits.Reader r = new Bits.Reader(body, 1);
        Bits.Writer w = new Bits.Writer(opcode);
        if (opcode == PLAYER_COORDS) {
            w.write(r.read(11), 11).write(r.read(13), 13).write(r.read(4), 4);
        }
        int known = r.read(8);
        w.write(known, 8);
        for (int i = 0; i < known; i++) {
            int update = r.read(1);
            w.write(update, 1);
            if (update == 0) {
                continue;
            }
            int still = r.read(1);
            w.write(still, 1);
            if (still == 0) {
                w.write(r.read(3), 3);
                continue;
            }
            int code = r.read(2);
            w.write(code, 2);
            if (code != 3) {
                w.write(r.read(2), 2);
            }
        }
        int entry = indexBits + 6 + 6 + 4 + idBits;
        while (r.remaining() >= entry) {
            w.write(r.read(indexBits), indexBits);
            w.write(r.read(6) & 0x1F, 5);
            w.write(r.read(6) & 0x1F, 5);
            w.write(r.read(4), 4);
            if (idBits > 0) {
                w.write(r.read(idBits), idBits);
            }
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L700-L1040
    private byte[] players(PacketReader in) {
        int count = in.u16();
        List<byte[]> entries = new ArrayList<>(count);
        List<Integer> appearances = new ArrayList<>();
        List<PlayerLook> looks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = in.u16();
            int type = in.u8();
            switch (type) {
                case 0 ->
                    entries.add(new PacketWriter(-1)
                            .u16(index)
                            .u8(0)
                            .u16(shown(in.u16()))
                            .toByteArray());
                case 1, 6, 7 -> {
                    int icon = type == 6 ? 0 : in.i32();
                    if (type == 7) {
                        in.u8();
                        in.u8();
                    }
                    String message = in.line();
                    // A muted or tutorial player's chat; a custom client is sent its text only when it is an admin's,
                    // and an authentic client staff's sees it marked.
                    if (type == 7) {
                        if (message.isEmpty()) {
                            continue;
                        }
                        message = "(Muted) " + message;
                        type = 1;
                    }
                    PacketWriter w = new PacketWriter(-1).u16(index).u8(type);
                    if (type == 1) {
                        w.u8(authenticIcon(icon));
                    }
                    entries.add(w.chat(message).toByteArray());
                }
                case 2 ->
                    entries.add(new PacketWriter(-1)
                            .u16(index)
                            .u8(2)
                            .u8(in.u8())
                            .u8(in.u8())
                            .u8(in.u8())
                            .toByteArray());
                case 3, 4 ->
                    entries.add(new PacketWriter(-1)
                            .u16(index)
                            .u8(type)
                            .u16(in.u16())
                            .u16(in.u16())
                            .toByteArray());
                case 5 -> {
                    PlayerLook look = PlayerLook.read(index, in);
                    appearances.add(entries.size());
                    looks.add(look);
                    entries.add(null);
                }
                case 9 -> {
                    in.u8();
                    in.u8();
                }
                default -> throw new IndexOutOfBoundsException("player update type " + type);
            }
        }
        // Every entry carries the receiving player's appearance id, which the server counts up for each change of
        // the player's appearance after the one its login sends.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1397-L1399
        if (looks.stream().anyMatch(look -> look.index() == selfIndex)) {
            ownAppearances++;
        }
        int ticket = Math.max(0, ownAppearances - 1);
        for (int i = 0; i < looks.size(); i++) {
            PlayerLook look = looks.get(i);
            boolean fullName = look.index() == selfIndex || looks.size() < 65;
            entries.set(appearances.get(i), look.authentic(ticket, fullName));
        }
        PacketWriter w = new PacketWriter(UPDATE_PLAYERS).u16(entries.size());
        for (byte[] entry : entries) {
            w.bytes(Arrays.copyOfRange(entry, 1, entry.length));
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L505-L577
    private void npcs(PacketReader in, List<byte[]> out) {
        int count = in.u16();
        List<byte[]> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = in.u16();
            int type = in.u8();
            switch (type) {
                case 1 -> {
                    int recipient = in.u16();
                    entries.add(new PacketWriter(-1)
                            .u16(index)
                            .u8(1)
                            .u16(recipient)
                            .chat(in.line())
                            .toByteArray());
                }
                case 2 ->
                    entries.add(new PacketWriter(-1)
                            .u16(index)
                            .u8(2)
                            .u8(in.u8())
                            .u8(in.u8())
                            .u8(in.u8())
                            .toByteArray());
                case 3, 4 -> in.bytes(4);
                case 5 -> in.u8();
                case 6 -> in.bytes(2);
                case 7 -> in.u16();
                default -> throw new IndexOutOfBoundsException("NPC update type " + type);
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        PacketWriter w = new PacketWriter(UPDATE_NPCS).u16(entries.size());
        for (byte[] entry : entries) {
            w.bytes(Arrays.copyOfRange(entry, 1, entry.length));
        }
        out.add(w.toByteArray());
    }

    // A custom client is sent each location's direction, and the boundaries removed within its view; an authentic one
    // neither.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L844-L853
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1190-L1229
    private static byte[] locations(PacketReader in, int opcode, boolean boundaries) {
        PacketWriter w = new PacketWriter(opcode);
        while (in.remaining() >= 5) {
            int id = in.u16();
            int x = in.u8();
            int y = in.u8();
            int direction = in.u8();
            if (!boundaries) {
                w.u16(id).u8(x).u8(y);
            } else if (id != REMOVED_LOCATION) {
                w.u16(id).u8(x).u8(y).u8(direction);
            }
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L855-L868
    private byte[] groundItems(PacketReader in) {
        PacketWriter w = new PacketWriter(GROUND_ITEMS);
        while (in.remaining() > 0) {
            int first = in.u8();
            if (first == 0xFF) {
                w.u8(0xFF).u8(in.u8()).u8(in.u8());
            } else {
                int id = (first << 8) | in.u8();
                int x = in.u8();
                int y = in.u8();
                // The top bit marks an item gone from the tile; one past the authentic items is never shown.
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/GroundItem.java#L156-L157
                if ((id & 0x7FFF) <= MAX_AUTHENTIC_ITEM) {
                    w.u16(id).u8(x).u8(y);
                }
            }
            if (bankNotes) {
                in.u8();
            }
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L432-L444
    private byte[] inventory(PacketReader in) {
        int count = in.u8();
        PacketWriter w = new PacketWriter(INVENTORY).u8(count);
        for (int slot = 0; slot < inventoryAmounts.length(); slot++) {
            inventoryAmounts.set(slot, 0);
        }
        for (int i = 0; i < count; i++) {
            int id = in.u16();
            int wielded = in.u8();
            boolean noted = in.u8() != 0;
            int amount = noted || items.isStackable(id) ? in.i32() : 0;
            w.u16((wielded << 15) | itemId(id, noted));
            if (amount > 0) {
                w.shortOrInt(amount);
            }
            setSlotAmount(i, amount > 0 ? amount : 1);
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L451-L466
    private byte[] inventoryItem(byte[] body, PacketReader in) {
        int slot = in.u8();
        if (body.length == 10) {
            setSlotAmount(slot, 0);
            return body;
        }
        int idAndWield = in.u16();
        boolean noted = in.u8() != 0;
        int amount = in.remaining() >= 4 ? in.i32() : 0;
        setSlotAmount(slot, amount > 0 ? amount : 1);
        PacketWriter w = new PacketWriter(INVENTORY_ITEM)
                .u8(slot)
                .u16((idAndWield & 0x8000) | itemId(idAndWield & 0x7FFF, noted));
        if (amount > 0) {
            w.shortOrInt(amount);
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1884-L1895
    private void bankOpen(PacketReader in, List<byte[]> out) {
        int count = in.u16();
        int max = in.u16();
        if ((members && count > 192) || (!members && count > 48)) {
            out.add(message(MESSAGE_GAME, "Warning: Unable to display all items in bank!"));
        }
        if (count > 192 - 48 && max > 192) {
            out.add(message(
                    MESSAGE_GAME,
                    "Warning: Bank is unauthentically large. Deposited items may not be visible to be withdrawn!"));
        }
        PacketWriter w = new PacketWriter(BANK_OPEN).u8(Math.min(count, 255)).u8(Math.min(max, 255));
        bankSlots.clear();
        for (int i = 0; i < count; i++) {
            int id = itemId(in.u16(), false);
            bankSlots.add(id);
            w.u16(id).shortOrInt(in.i32());
        }
        out.add(w.toByteArray());
    }

    // A custom client is told an emptied slot holds item 0; an authentic one, the item that was there.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1996-L2001
    private byte[] bankUpdate(PacketReader in) {
        int slot = in.u8();
        int id = in.u16();
        int amount = in.i32();
        if (amount == 0) {
            // The later items move up a slot.
            id = slot < bankSlots.size() ? bankSlots.remove(/* index */ slot) : id;
        } else {
            id = shown(id);
            if (slot < bankSlots.size()) {
                bankSlots.set(slot, id);
            } else {
                bankSlots.add(id);
            }
        }
        return new PacketWriter(BANK_UPDATE).u8(slot).u16(id).shortOrInt(amount).toByteArray();
    }

    // An authentic client is not shown the items past its limit, which reach a custom client as placeholders.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1943-L1970
    private byte[] shop(PacketReader in) {
        int count = in.u8();
        byte[] header = in.bytes(4);
        List<int[]> kept = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = in.u16();
            int amount = in.u16();
            int base = in.u16();
            if (id <= MAX_AUTHENTIC_ITEM) {
                kept.add(new int[] {id, amount, base});
            }
        }
        PacketWriter w = new PacketWriter(SHOP_OPEN).u8(kept.size()).bytes(header);
        for (int[] item : kept) {
            w.u16(item[0]).u16(item[1]).u16(item[2]);
        }
        return w.toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1760-L1800
    private void tradeOtherItems(PacketReader in, List<byte[]> out) {
        List<int[]> offer = offer(in);
        PacketWriter w = new PacketWriter(TRADE_OTHER_ITEMS).u8(offer.size());
        for (int[] item : offer) {
            notedNotice(out, item, "@whi@Other player offered @gre@%d @yel@%s");
            w.u16(itemId(item[0], item[2] != 0)).i32(item[1]);
        }
        out.add(w.toByteArray());
    }

    // The 235 generator sends nothing when an offered item is past its limit.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L310-L334
    private void tradeConfirm(PacketReader in, List<byte[]> out) {
        String target = in.line();
        List<int[]> theirs = offer(in);
        List<int[]> mine = offer(in);
        for (int[] item : theirs) {
            notedNotice(out, item, "@ran@Please Confirm: @whi@Other player is offering @gre@%d @yel@%s");
        }
        if (theirs.stream().anyMatch(item -> item[0] > MAX_AUTHENTIC_ITEM)) {
            return;
        }
        PacketWriter w = new PacketWriter(TRADE_CONFIRM).zeroQuoted(target).u8(theirs.size());
        for (int[] item : theirs) {
            w.u16(item[0]).i32(item[1]);
        }
        w.u8(mine.size());
        for (int[] item : mine) {
            w.u16(shown(item[0])).i32(item[1]);
        }
        out.add(w.toByteArray());
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L352-L372
    private void duelOpponentItems(PacketReader in, List<byte[]> out) {
        List<int[]> stake = offer(in);
        PacketWriter w = new PacketWriter(DUEL_OPPONENT_ITEMS).u8(stake.size());
        for (int[] item : stake) {
            notedNotice(out, item, "@whi@Other player is staking @gre@%d @yel@%s");
            w.u16(itemId(item[0], item[2] != 0)).i32(item[1]);
        }
        out.add(w.toByteArray());
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L376-L395
    private void duelConfirm(PacketReader in, List<byte[]> out) {
        String target = in.line();
        List<int[]> theirs = offer(in);
        List<int[]> mine = offer(in);
        PacketWriter w = new PacketWriter(DUEL_CONFIRM).zeroQuoted(target).u8(theirs.size());
        for (int[] item : theirs) {
            notedNotice(out, item, "@ran@Please Confirm: @whi@Other player is staking @gre@%d @yel@%s");
            w.u16(shown(item[0])).i32(item[1]);
        }
        w.u8(mine.size());
        for (int[] item : mine) {
            w.u16(shown(item[0])).i32(item[1]);
        }
        out.add(w.bytes(in.bytes(4)).toByteArray());
    }

    // A list of offered items: each its id, whether noted (when the world has bank notes), and amount.
    private List<int[]> offer(PacketReader in) {
        int count = in.u8();
        List<int[]> offer = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = in.u16();
            int noted = bankNotes ? in.u8() : 0;
            offer.add(new int[] {id, in.i32(), noted});
        }
        return offer;
    }

    // An authentic client cannot show a noted item, so the server names each one in a message.
    private void notedNotice(List<byte[]> out, int[] item, String format) {
        if (item[2] != 0) {
            ItemDef def = items.get(item[0]);
            String name = def != null ? def.name() : items.nameOf(item[0]);
            out.add(message(MESSAGE_QUEST, String.format(format, item[1], name)));
        }
    }

    // Each section keeps the 18 skills an authentic client has; the extra ones follow Thieving.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L212-L291
    private static byte[] stats(byte[] body) {
        int skills = (body.length - 2) / 6;
        PacketWriter w = new PacketWriter(STATS);
        w.bytes(Arrays.copyOfRange(body, 1, 1 + AUTHENTIC_SKILLS));
        w.bytes(Arrays.copyOfRange(body, 1 + skills, 1 + skills + AUTHENTIC_SKILLS));
        int xp = 1 + 2 * skills;
        w.bytes(Arrays.copyOfRange(body, xp, xp + 4 * AUTHENTIC_SKILLS));
        return w.u8(body[body.length - 1]).toByteArray();
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L502-L515
    private static byte[] serverMessage(PacketReader in) {
        in.i32();
        int type = in.u8();
        int info = in.u8();
        PacketWriter w = new PacketWriter(SERVER_MESSAGE).u8(type).u8(info).zeroQuoted(in.line());
        if ((info & 1) != 0) {
            w.zeroQuoted(in.line()).zeroQuoted(in.line());
        }
        if ((info & 2) != 0) {
            w.zeroQuoted(in.line());
        }
        return w.toByteArray();
    }

    private static byte[] message(int type, String text) {
        return new PacketWriter(SERVER_MESSAGE).u8(type).u8(0).zeroQuoted(text).toByteArray();
    }

    // The 235 message carries an id the custom one does not: three zero bytes, the world, and a count of the private
    // messages sent, which only has to differ between messages. The world is not known here; 1 stands in for it.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L517-L533
    private byte[] privateMessage(PacketReader in) {
        String name = in.line();
        String former = in.line();
        int icon = in.i32();
        byte[] message = in.rest();
        // Global chat comes from "Global$" to an authentic client, with the speaker's name in the text.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1301-L1317
        if (name.startsWith(GLOBAL) && name.length() > GLOBAL.length()) {
            String speaker = name.substring(GLOBAL.length());
            name = GLOBAL;
            former = GLOBAL;
            message = new PacketWriter(-1)
                    .chat("@ora@[@gre@" + speaker + "@ora@]:@cya@ " + chatText(message))
                    .toByteArray();
            message = Arrays.copyOfRange(message, 1, message.length);
        }
        privateMessages = (privateMessages + 1) & 0xFFFFFF;
        return new PacketWriter(PRIVATE_MESSAGE)
                .zeroQuoted(name)
                .zeroQuoted(former)
                .u8(authenticIcon(icon))
                .u8(0)
                .u8(0)
                .u8(0)
                .u16(1)
                .u8(privateMessages >> 16)
                .u8(privateMessages >> 8)
                .u8(privateMessages)
                .bytes(message)
                .toByteArray();
    }

    // Chat text: its length in one byte below 128 and two from there, then the text Huffman-coded.
    private static String chatText(byte[] coded) {
        int length = coded[0] & 0xFF;
        int offset = 1;
        if (length >= 128) {
            length = ((length << 8) | (coded[1] & 0xFF)) - 32768;
            offset = 2;
        }
        return Huffman.decode(coded, offset, length);
    }

    private static String box(String text) {
        return text.length() > MAX_BOX_LENGTH ? text.substring(0, MAX_BOX_LENGTH) : text;
    }

    // An authentic client shows five options; the server tells it the rest in messages.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1212-L1220
    private static void optionsMenu(PacketReader in, List<byte[]> out) {
        int count = in.u8();
        List<String> options = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            options.add(in.line());
        }
        if (count > MAX_MENU_OPTIONS) {
            out.add(message(
                    MESSAGE_QUEST, "@red@There is a bug in the server which prevented you from seeing all options."));
            out.add(message(MESSAGE_QUEST, "@ran@Please report this! @whi@You are missing these options:"));
            for (int i = MAX_MENU_OPTIONS; i < count; i++) {
                out.add(message(MESSAGE_QUEST, "@ora@" + (i + 1) + ") @whi@" + options.get(i)));
            }
        }
        PacketWriter w = new PacketWriter(OPTIONS_MENU).u8(Math.min(count, MAX_MENU_OPTIONS));
        for (int i = 0; i < Math.min(count, MAX_MENU_OPTIONS); i++) {
            w.zeroQuoted(options.get(i));
        }
        out.add(w.toByteArray());
    }

    // A custom client is sent the address as text, and the days until a change of recovery questions applies, or 0;
    // an authentic one the address in four bytes, and the days since the questions changed, which the 0 leaves
    // unknown: it is sent as never.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/PayloadCustomGenerator.java#L792-L803
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/generators/impl/Payload235Generator.java#L541-L603
    private static byte[] welcome(PacketReader in) {
        String ip = in.line();
        int days = in.u16();
        int untilRecovery = in.u16();
        PacketWriter w = new PacketWriter(WELCOME);
        for (byte octet : addressBytes(ip)) {
            w.u8(octet);
        }
        w.u16(days);
        w.u8(untilRecovery > 0 ? 14 - untilRecovery : 200);
        return w.u16(1).toByteArray();
    }

    // An IPv6 address is a 6, then the low bytes of its last two groups.
    private static byte[] addressBytes(String ip) {
        byte[] out = new byte[4];
        if (!ip.contains(":")) {
            String[] octets = ip.split("\\.");
            for (int i = 0; i < 4 && octets.length == 4; i++) {
                out[i] = (byte) parseOr0(octets[i], 10);
            }
            return out;
        }
        out[0] = 6;
        String[] groups = ip.split(":");
        int at = 3;
        for (int i = groups.length - 1; i > 0 && at > 1; i--) {
            out[at--] = (byte) parseOr0(groups[i], 16);
        }
        return out;
    }

    private static int parseOr0(String number, int radix) {
        try {
            return Integer.parseInt(number, radix);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L2185-L2201
    private static byte[] onlineList(PacketReader in) {
        int online = in.u16();
        StringBuilder text = new StringBuilder("@lre@Players online @gre@(" + online + ") %");
        boolean first = true;
        while (in.remaining() > 0) {
            String name = in.line();
            in.i32();
            String location = in.line();
            if (!first) {
                text.append(" @mag@; ");
            }
            first = false;
            text.append("@whi@").append(name);
            if (!location.isEmpty()) {
                text.append(" @yel@(").append(location).append(")");
            }
        }
        return new PacketWriter(BOX).zeroQuoted(box(text.toString())).toByteArray();
    }

    // A custom client is sent a noted item as itself, and an item past its limit as a placeholder past the authentic
    // ones; an authentic client is sent stand-ins it can show.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Item.java#L118-L152
    private int itemId(int id, boolean noted) {
        return noted ? SHANTAY_PASS : shown(id);
    }

    private int shown(int id) {
        if (id <= MAX_AUTHENTIC_ITEM) {
            return id;
        }
        return items.isStackable(id) ? BRONZE_ARROWS : IRON_MACE;
    }

    // The custom icon is a colour; the authentic one 1 for a player moderator and 2 for staff, or an admin's choice.
    // Without custom rank display a moderator has a player moderator's colour, and is shown as one.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L3519-L3588
    static int authenticIcon(int icon) {
        if (icon == 0) {
            return 0;
        }
        if (icon == 0x03FFFFFF) {
            return 1;
        }
        return icon > 0 && icon < 256 ? icon : 2;
    }
}
