package openrsc.nav.probe;

import java.util.Locale;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.DoorDef;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.SpellDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;

/**
 * What a map calls a transport, such as "Climb down" or "Open, heading east": the menu verb the game
 * shows on the thing used, then which way the crossing goes. Derived from a
 * {@link DeclarativeTransport}'s engagement and geometry. A scenery or boundary's own name is
 * left out.
 */
public final class TransportLabels {

    // Past this many tiles a compass point would read as a claim about a place.
    private static final int COMPASS_RANGE = 24;

    /** Never null: a transport whose engagement names no verb is labelled by its {@link Transport#name()}. */
    public static String of(Transport t, ServerData defs) {
        String action = action(t, defs);
        if (action == null) {
            return t.name();
        }
        String heading = heading(t, action);
        return heading == null ? action : action + ", " + heading;
    }

    private static String action(Transport t, ServerData defs) {
        if (!(t instanceof DeclarativeTransport d)) {
            return null;
        }
        EngageSpec spec = d.engageSpec();
        return switch (spec.action()) {
            case EngageSpec.Action.Interact it -> command(spec.target(), it.command(), defs);
            case EngageSpec.Action.NpcCommand cmd -> {
                EngageSpec.Target.Npcs npcs = (EngageSpec.Target.Npcs) spec.target();
                NpcDef def = npcs.npcIds().length == 0 ? null : defs.npc(npcs.npcIds()[0]);
                yield "Command " + cmd.command() + " on " + (def == null ? "NPC" : def.name());
            }
            case EngageSpec.Action.UseItem use ->
                switch (use.selector()) {
                    case EngageSpec.ItemSelector.BestCutter c -> "Cut through";
                    case EngageSpec.ItemSelector.Fixed f ->
                        "Use the " + lower(defs.itemDefs().nameOf(f.itemId()));
                };
            case EngageSpec.Action.ItemCommand cmd ->
                switch (cmd.selector()) {
                    case EngageSpec.ItemSelector.Fixed f ->
                        "Rub the " + lower(defs.itemDefs().nameOf(f.itemId()));
                    case EngageSpec.ItemSelector.BestCutter c -> "Rub";
                };
            case EngageSpec.Action.Cast cast -> {
                SpellDef sp = defs.spell(cast.spellId());
                yield sp == null ? "Cast" : "Cast " + sp.name();
            }
            case EngageSpec.Action.Bespoke b ->
                switch (b.script()) {
                    case SHANTAY_PASS -> "Buy a pass and cross";
                    case BANANA_PLANTATION -> "Fill the crate and stow away";
                    case GREW_ISLAND_ROPE_SWING -> "Tie the rope and swing";
                    case KEEP_LE_FAYE_SHIP -> "Board with a crewmate";
                    case VIYELDI_ROPE_DOWN -> "Search the beam and go down the rope";
                    case SCOT_RUTH_TUNNEL -> "Buy Scot Ruth's tunnel and enter it";
                    case UNDERGROUND_PASS_OLD_BRIDGE -> "Burn the bridge with a lit arrow";
                    case UNDERGROUND_PASS_EAST_GRILL -> "Tie a rope to the wall grill and climb across";
                    case ERNEST_BASEMENT_MAZE -> "Pull the levers and cross the maze";
                    case PARAMAYA_DORMITORY -> "Buy a rest ticket and climb";
                };
        };
    }

    private static String command(EngageSpec.Target target, int command, ServerData defs) {
        String raw =
                switch (target) {
                    case EngageSpec.Target.Scenery s -> {
                        ObjectDef def = defs.object(s.objectId());
                        yield def == null ? null : (command == 1 ? def.command1() : def.command2());
                    }
                    case EngageSpec.Target.Boundary b -> {
                        DoorDef def = defs.doorDefs().get(b.boundaryId());
                        yield def == null ? null : (command == 1 ? def.command1() : def.command2());
                    }
                    case EngageSpec.Target.Npcs npcs -> {
                        NpcDef def = npcs.npcIds().length == 0 ? null : defs.npc(npcs.npcIds()[0]);
                        yield def == null ? "Talk to the ferryman" : "Talk to " + def.name();
                    }
                    case EngageSpec.Target.Self self -> null;
                };
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.equalsIgnoreCase("walkto")) {
            return "Walk through";
        }
        String words = raw.replace('-', ' ').trim();
        return Character.toUpperCase(words.charAt(0)) + words.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String heading(Transport t, String action) {
        Point entry = t.entries() == null || t.entries().isEmpty()
                ? null
                : t.entries().get(0);
        if (entry == null) {
            return null;
        }
        Point dest = t.destinationFrom(entry);
        int from = entry.y() / CollisionMap.FLOOR_HEIGHT;
        int to = dest.y() / CollisionMap.FLOOR_HEIGHT;
        if (from != to) {
            if (to == UNDERGROUND) {
                return "into the underground";
            }
            if (from == UNDERGROUND) {
                return "to the surface";
            }
            return saysVertical(action) ? null : (to > from ? "up" : "down");
        }
        int dx = dest.x() - entry.x();
        int dz = dest.y() - entry.y();
        if (Math.max(Math.abs(dx), Math.abs(dz)) > COMPASS_RANGE) {
            return null;
        }
        if (dx == 0 && dz == 0) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        if (dz < 0) {
            b.append("north");
        } else if (dz > 0) {
            b.append("south");
        }
        if (dx > 0) {
            b.append("east");
        } else if (dx < 0) {
            b.append("west");
        }
        return b.isEmpty() ? null : "heading " + b;
    }

    private static boolean saysVertical(String action) {
        String a = action.toLowerCase(Locale.ROOT);
        return a.contains("up") || a.contains("down");
    }

    private static final int UNDERGROUND = 3;

    private static String lower(String name) {
        return name == null ? "item" : name.toLowerCase(Locale.ROOT);
    }

    private TransportLabels() {}
}
