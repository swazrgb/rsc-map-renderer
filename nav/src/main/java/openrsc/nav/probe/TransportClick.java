package openrsc.nav.probe;

import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.Packet;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.packets.AtObject2Packet;
import openrsc.bot.api.packets.AtObjectPacket;
import openrsc.bot.api.packets.AtWallObject2Packet;
import openrsc.bot.api.packets.AtWallObjectPacket;
import openrsc.bot.api.packets.CastOnSceneryPacket;
import openrsc.bot.api.packets.CastOnSelfPacket;
import openrsc.bot.api.packets.ItemCommandPacket;
import openrsc.bot.api.packets.NpcCommand1Packet;
import openrsc.bot.api.packets.NpcCommand2Packet;
import openrsc.bot.api.packets.TalkToNpcPacket;
import openrsc.bot.api.packets.UseItemOnBoundaryPacket;
import openrsc.bot.api.packets.UseItemOnObjectPacket;
import openrsc.bot.api.packets.UseWornItemOnBoundaryPacket;
import openrsc.bot.api.packets.WornItemCommandPacket;
import openrsc.gamedata.ServerData;
import openrsc.nav.transports.EngageSpec;
import openrsc.nav.walk.Yield;

/**
 * The one packet that takes a declarative transport once the walk stands on its entry: the click
 * alone, with no approach and no dialogue.
 */
final class TransportClick {

    private TransportClick() {}

    /**
     * The click, or why there is none: {@code missing} when there never will be one, neither when
     * there is none yet because no NPC the transport talks to is in view.
     */
    record Click(@Nullable Packet packet, @Nullable String missing) {}

    static Click of(EngageSpec spec, Inventory pack, ServerData defs, List<? extends Yield.Sighting> npcs) {
        EngageSpec.Action action = spec.action();
        if (action instanceof EngageSpec.Action.Bespoke b) {
            return new Click(null, "a scripted crossing (" + b.script() + ")");
        }
        return switch (spec.target()) {
            case EngageSpec.Target.Scenery o ->
                switch (action) {
                    case EngageSpec.Action.Interact i ->
                        new Click(
                                i.command() == 2 ? new AtObject2Packet(o.x(), o.y()) : new AtObjectPacket(o.x(), o.y()),
                                null);
                    case EngageSpec.Action.UseItem u ->
                        item(u.selector(), pack, defs, slot -> new UseItemOnObjectPacket(o.x(), o.y(), slot), null);
                    case EngageSpec.Action.Cast c ->
                        new Click(new CastOnSceneryPacket(c.spellId(), o.x(), o.y()), null);
                    default -> new Click(null, action + " on scenery");
                };
            case EngageSpec.Target.Boundary w -> {
                if (w.dir() < 0) {
                    yield new Click(null, "a boundary whose edge is unknown");
                }
                yield switch (action) {
                    case EngageSpec.Action.Interact i ->
                        new Click(
                                i.command() == 2
                                        ? new AtWallObject2Packet(w.x(), w.y(), w.dir())
                                        : new AtWallObjectPacket(w.x(), w.y(), w.dir()),
                                null);
                    case EngageSpec.Action.UseItem u ->
                        item(
                                u.selector(),
                                pack,
                                defs,
                                slot -> new UseItemOnBoundaryPacket(w.x(), w.y(), w.dir(), slot),
                                id -> new UseWornItemOnBoundaryPacket(w.x(), w.y(), w.dir(), id));
                    default -> new Click(null, action + " on a boundary");
                };
            }
            case EngageSpec.Target.Npcs n -> {
                for (Yield.Sighting s : npcs) {
                    for (int index : n.serverIndexes()) {
                        if (s.serverIndex() == index) {
                            if (action instanceof EngageSpec.Action.NpcCommand cmd) {
                                yield new Click(
                                        cmd.command() == 1
                                                ? new NpcCommand1Packet(index)
                                                : new NpcCommand2Packet(index),
                                        null);
                            }
                            yield new Click(new TalkToNpcPacket(index), null);
                        }
                    }
                }
                yield new Click(null, null);
            }
            case EngageSpec.Target.Self self ->
                switch (action) {
                    case EngageSpec.Action.Cast c -> new Click(new CastOnSelfPacket(c.spellId()), null);
                    case EngageSpec.Action.ItemCommand ic ->
                        item(ic.selector(), pack, defs, ItemCommandPacket::new, WornItemCommandPacket::new);
                    default -> new Click(null, action + " on ourselves");
                };
        };
    }

    private interface BySlot {
        Packet at(int slot);
    }

    private interface ById {
        Packet of(int itemId);
    }

    private static Click item(
            EngageSpec.ItemSelector selector, Inventory pack, ServerData defs, BySlot packet, @Nullable ById worn) {
        InventorySlot slot = selector.pickFrom(pack, defs);
        if (slot == null) {
            return new Click(null, "nothing aboard to use");
        }
        // With an equipment tab a worn item has left the inventory, so no slot names it:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L383
        if (slot.equipped() && defs.world().rules().equipmentTab()) {
            return worn == null
                    ? new Click(null, "a worn item has no pack slot to use it from")
                    : new Click(worn.of(slot.id()), null);
        }
        return new Click(packet.at(slot.slot()), null);
    }
}
