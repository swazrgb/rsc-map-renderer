package openrsc.sessionproxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import openrsc.bot.api.GameObject;
import openrsc.bot.api.GameState;
import openrsc.bot.api.GroundItem;
import openrsc.bot.api.Npc;
import openrsc.bot.api.Packet;
import openrsc.bot.api.Player;
import openrsc.bot.api.StayWalk;
import openrsc.bot.api.WallObject;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.packets.AtObject2Packet;
import openrsc.bot.api.packets.AtObjectPacket;
import openrsc.bot.api.packets.AtWallObject2Packet;
import openrsc.bot.api.packets.AtWallObjectPacket;
import openrsc.bot.api.packets.AttackNpcPacket;
import openrsc.bot.api.packets.AttackPlayerPacket;
import openrsc.bot.api.packets.DialogueReplyPacket;
import openrsc.bot.api.packets.DropPacket;
import openrsc.bot.api.packets.ItemCommandPacket;
import openrsc.bot.api.packets.ItemEquipPacket;
import openrsc.bot.api.packets.ItemUnequipFromEquipmentPacket;
import openrsc.bot.api.packets.ItemUnequipPacket;
import openrsc.bot.api.packets.NpcCommand1Packet;
import openrsc.bot.api.packets.NpcCommand2Packet;
import openrsc.bot.api.packets.PickupGroundItemPacket;
import openrsc.bot.api.packets.PlayerDuelPacket;
import openrsc.bot.api.packets.PlayerFollowPacket;
import openrsc.bot.api.packets.TalkToNpcPacket;
import openrsc.bot.api.packets.TradeRequestPacket;
import openrsc.bot.api.packets.UseItemOnBoundaryPacket;
import openrsc.bot.api.packets.UseItemOnGroundItemPacket;
import openrsc.bot.api.packets.UseItemOnItemPacket;
import openrsc.bot.api.packets.UseItemOnNpcPacket;
import openrsc.bot.api.packets.UseItemOnObjectPacket;
import openrsc.bot.api.packets.WornItemCommandPacket;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import org.jetbrains.annotations.Nullable;

/**
 * An entry of the game's menus that the live map asks the player to take.
 *
 * <p>{@code (x, z)} is the target's tile. {@code id} is an NPC's or player's server index, a ground item's or a
 * scenery's id, the other item of {@link Verb#USE_ITEM_ON_ITEM}, or the option an {@link Verb#ANSWER} picks (negative
 * closes the conversation). {@code dir} is a scenery's or boundary's facing. {@code item} is the carried item the verb
 * acts on or uses, its bank notes when {@code noted}: a note carries its item's id. The other item of
 * {@link Verb#USE_ITEM_ON_ITEM} is its unnoted stack when the pack holds one, its notes otherwise.
 */
public record MapAction(Verb verb, int x, int z, int id, int dir, int item, boolean noted) {

    public enum Verb {
        OBJECT,
        OBJECT2,
        WALL_OBJECT,
        WALL_OBJECT2,
        GROUND_ITEM,
        NPC_TALK,
        NPC_ATTACK,
        NPC_COMMAND1,
        NPC_COMMAND2,
        PLAYER_ATTACK,
        PLAYER_TRADE,
        PLAYER_FOLLOW,
        PLAYER_DUEL,
        ANSWER,
        ITEM_COMMAND,
        ITEM_WIELD,
        ITEM_UNWIELD,
        ITEM_DROP,
        USE_ITEM_ON_OBJECT,
        USE_ITEM_ON_WALL_OBJECT,
        USE_ITEM_ON_NPC,
        USE_ITEM_ON_GROUND_ITEM,
        USE_ITEM_ON_ITEM;

        /** The verb a menu names in lower case, as the map sends it; null for none. */
        public static @Nullable Verb named(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The packets that take the action, or why there are none. */
    record Click(@Nullable List<Packet> packets, @Nullable String missing) {
        static Click of(Packet... packets) {
            return new Click(List.of(packets), null);
        }

        static Click none(String why) {
            return new Click(null, why);
        }
    }

    /** Whether it is taken wherever the player stands: an answer, or a verb on carried items alone. */
    boolean inHand() {
        return switch (verb) {
            case ANSWER, ITEM_COMMAND, ITEM_WIELD, ITEM_UNWIELD, ITEM_DROP, USE_ITEM_ON_ITEM -> true;
            default -> false;
        };
    }

    /** What it acts on as {@code s} sees it; null for one {@link #inHand}, or an NPC or player out of sight. */
    @Nullable
    Targetable target(GameState s) {
        return switch (verb) {
            case OBJECT, OBJECT2, USE_ITEM_ON_OBJECT -> new GameObject(id, x, z, dir);
            case WALL_OBJECT, WALL_OBJECT2, USE_ITEM_ON_WALL_OBJECT -> new WallObject(id, x, z, dir);
            case GROUND_ITEM, USE_ITEM_ON_GROUND_ITEM -> new GroundItem(id, x, z, false);
            case NPC_TALK, NPC_ATTACK, NPC_COMMAND1, NPC_COMMAND2, USE_ITEM_ON_NPC -> npc(s);
            case PLAYER_TRADE -> {
                Player p = player(s);
                yield p == null ? null : p.asTradeTarget();
            }
            case PLAYER_ATTACK, PLAYER_FOLLOW, PLAYER_DUEL -> player(s);
            default -> null;
        };
    }

    /**
     * What to send to take it from where the player stands. A click on scenery, or any click in a fight, rides behind
     * a walk to the player's own tile ({@link StayWalk}).
     */
    Click click(GameState s) {
        Click c = bare(s);
        boolean scenery = verb == Verb.OBJECT || verb == Verb.OBJECT2 || verb == Verb.USE_ITEM_ON_OBJECT;
        if (c.packets() == null || inHand() || !StayWalk.ahead(s.inCombat(), scenery)) {
            return c;
        }
        List<Packet> out = new ArrayList<>();
        out.add(StayWalk.at(s.pos()));
        out.addAll(c.packets());
        return new Click(List.copyOf(out), null);
    }

    private Click bare(GameState s) {
        return switch (verb) {
            case OBJECT -> Click.of(new AtObjectPacket(x, z));
            case OBJECT2 -> Click.of(new AtObject2Packet(x, z));
            case WALL_OBJECT -> Click.of(new AtWallObjectPacket(x, z, dir));
            case WALL_OBJECT2 -> Click.of(new AtWallObject2Packet(x, z, dir));
            case GROUND_ITEM -> Click.of(new PickupGroundItemPacket(x, z, id));
            case NPC_TALK -> Click.of(new TalkToNpcPacket(id));
            case NPC_ATTACK -> Click.of(new AttackNpcPacket(id));
            case NPC_COMMAND1 -> Click.of(new NpcCommand1Packet(id));
            case NPC_COMMAND2 -> Click.of(new NpcCommand2Packet(id));
            case PLAYER_ATTACK -> Click.of(new AttackPlayerPacket(id));
            case PLAYER_TRADE -> Click.of(new TradeRequestPacket(id));
            case PLAYER_FOLLOW -> Click.of(new PlayerFollowPacket(id));
            case PLAYER_DUEL -> Click.of(new PlayerDuelPacket(id));
            case ANSWER -> answer(s);
            case ITEM_COMMAND -> command(s);
            case ITEM_WIELD -> withLoose(s, slot -> new ItemEquipPacket(slot.slot()));
            case ITEM_UNWIELD -> takeOff(s);
            case ITEM_DROP -> withLoose(s, slot -> new DropPacket(slot.slot(), slot.amount()));
            case USE_ITEM_ON_OBJECT -> withLoose(s, slot -> new UseItemOnObjectPacket(x, z, slot.slot()));
            case USE_ITEM_ON_WALL_OBJECT -> withLoose(s, slot -> new UseItemOnBoundaryPacket(x, z, dir, slot.slot()));
            case USE_ITEM_ON_NPC -> withLoose(s, slot -> new UseItemOnNpcPacket(id, slot.slot()));
            case USE_ITEM_ON_GROUND_ITEM -> withLoose(s, slot -> new UseItemOnGroundItemPacket(x, z, slot.slot(), id));
            case USE_ITEM_ON_ITEM -> {
                InventorySlot used = loose(s, item, noted);
                InventorySlot on = loose(s, id, false);
                if (on == null) {
                    on = loose(s, id, true);
                }
                yield used == null || on == null
                        ? Click.none("the pack holds no " + (used == null ? item : id))
                        : Click.of(new UseItemOnItemPacket(used.slot(), on.slot()));
            }
        };
    }

    private Click answer(GameState s) {
        if (id < 0) {
            // Any packet but an answer drops the open menu, which ends the conversation; this one moves nowhere:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/PayloadProcessorManager.java#L175
            return Click.of(StayWalk.at(s.pos()));
        }
        if (!s.dialog().menuOpen() || id >= s.dialog().options().size()) {
            return Click.none("no menu with that option is open");
        }
        return Click.of(new DialogueReplyPacket(id));
    }

    // With an equipment tab a worn item has left the inventory, so its command names the item, not a slot:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L45
    private Click command(GameState s) {
        InventorySlot slot = slotOf(s, item, null, noted);
        if (slot == null) {
            return Click.none("the pack holds no " + item);
        }
        return slot.equipped() && s.serverData().world().rules().equipmentTab()
                ? Click.of(new WornItemCommandPacket(item))
                : Click.of(new ItemCommandPacket(slot.slot()));
    }

    // With an equipment tab a worn item has left the inventory, so it comes off by its equipment slot:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUnequip.java#L48
    private Click takeOff(GameState s) {
        InventorySlot slot = slotOf(s, item, true, false);
        if (slot == null) {
            return Click.none("nothing worn is " + item);
        }
        if (!s.serverData().world().rules().equipmentTab()) {
            return Click.of(new ItemUnequipPacket(slot.slot()));
        }
        ItemDef def = s.serverData().item(item);
        return def == null
                ? Click.none("no definition for " + item)
                : Click.of(new ItemUnequipFromEquipmentPacket(def.wearSlot()));
    }

    private interface BySlot {
        Packet of(InventorySlot slot);
    }

    private Click withLoose(GameState s, BySlot packet) {
        InventorySlot slot = loose(s, item, noted);
        return slot == null ? Click.none("the pack holds no " + item) : Click.of(packet.of(slot));
    }

    private static @Nullable InventorySlot loose(GameState s, int item, boolean noted) {
        return slotOf(s, item, false, noted);
    }

    private static @Nullable InventorySlot slotOf(GameState s, int item, @Nullable Boolean worn, boolean noted) {
        for (InventorySlot slot : s.inventory().slots()) {
            if (slot.item().id() == item && slot.noted() == noted && (worn == null || slot.equipped() == worn)) {
                return slot;
            }
        }
        return null;
    }

    private @Nullable Npc npc(GameState s) {
        for (Npc n : s.npcs()) {
            if (n.serverIndex() == id) {
                return n;
            }
        }
        return null;
    }

    private @Nullable Player player(GameState s) {
        for (Player p : s.players()) {
            if (p.serverIndex() == id) {
                return p;
            }
        }
        return null;
    }

    /** The tile it is first walked toward: its target's. */
    Point tile() {
        return Point.of(x, z);
    }
}
