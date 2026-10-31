package openrsc.nav.transports;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;

/**
 * How a transport is fired, as data: an action on a target, then the menus to answer. It carries no
 * walking and no execution code; whoever drives the route interprets it.
 *
 * @param dialog keyword groups for the menus the engagement opens; empty when it opens none
 * @param awaitsDialog whether the engagement starts a conversation that must run to its end before
 *     the player moves, so the target is not clicked again meanwhile; true whenever {@code dialog}
 *     is non-empty
 * @param stand where the player must be before the action is sent; {@code null} takes the target's
 *     {@link Target#defaultStand()}
 * @param sequencedDialog whether menu N is answered by group N, by position, rather than by whichever
 *     group matches its options
 */
public record EngageSpec(
        Action action,
        Target target,
        List<String[]> dialog,
        boolean awaitsDialog,
        Stand stand,
        boolean sequencedDialog) {

    /** Where the player must be before the action is sent. */
    public enum Stand {
        /** On the transport's entry tile exactly: the landing, or whether the click works, depends on it. */
        ON_ENTRY,
        /** On any tile from which the server accepts the click on the target. */
        IN_REACH,
        /** Anywhere the target is in view. */
        IN_VIEW
    }

    public sealed interface Action {
        /** The object's first or second option, 1 or 2. */
        record Interact(int command) implements Action {
            public Interact {
                if (command != 1 && command != 2) {
                    throw new IllegalArgumentException("command must be 1 or 2, got " + command);
                }
            }
        }

        /** The NPC's first or second command option, 1 or 2, rather than talking to it. */
        record NpcCommand(int command) implements Action {
            public NpcCommand {
                if (command != 1 && command != 2) {
                    throw new IllegalArgumentException("command must be 1 or 2, got " + command);
                }
            }
        }

        record UseItem(ItemSelector selector) implements Action {}

        /** The inventory item's own command, such as rubbing an amulet; its target is {@link Target#SELF}. */
        record ItemCommand(ItemSelector selector) implements Action {}

        /** The spell cast on {@link Target#SELF}, or at a {@link Target.Scenery}. */
        record Cast(int spellId) implements Action {}

        /**
         * A crossing that takes more than one engagement, driven by the hand-written script
         * {@code script} names. An interpreter that has no such script cannot take the hop.
         */
        record Bespoke(BespokeScript script) implements Action {
            public Bespoke {
                Objects.requireNonNull(script, "a bespoke engagement names the script that drives it");
            }
        }

        Action INTERACT_1 = new Interact(1);
        Action INTERACT_2 = new Interact(2);

        static Action useItem(int itemId) {
            return new UseItem(new ItemSelector.Fixed(itemId));
        }

        static Action itemCommand(int itemId) {
            return new ItemCommand(new ItemSelector.Fixed(itemId));
        }
    }

    /** The crossings an interpreter must script by hand, one per {@link Action.Bespoke} transport. */
    public enum BespokeScript {
        SHANTAY_PASS,
        BANANA_PLANTATION,
        GREW_ISLAND_ROPE_SWING,
        KEEP_LE_FAYE_SHIP,
        VIYELDI_ROPE_DOWN,
        SCOT_RUTH_TUNNEL,
        UNDERGROUND_PASS_OLD_BRIDGE,
        UNDERGROUND_PASS_EAST_GRILL,
        ERNEST_BASEMENT_MAZE,
        PARAMAYA_DORMITORY
    }

    /** Which inventory item an item action uses. */
    public sealed interface ItemSelector {
        record Fixed(int itemId) implements ItemSelector {}

        /** A knife if one is carried, else the first carried melee weapon. */
        record BestCutter() implements ItemSelector {}

        /** The slot of {@code pack} this selects, or {@code null} when no item qualifies. */
        default @Nullable InventorySlot pickFrom(Inventory pack, ServerData defs) {
            return switch (this) {
                case Fixed f -> {
                    for (InventorySlot it : pack.slots()) {
                        if (it.id() == f.itemId()) {
                            yield it;
                        }
                    }
                    yield null;
                }
                case BestCutter c -> {
                    InventorySlot weapon = null;
                    for (InventorySlot it : pack.slots()) {
                        if (it.id() == ItemId.KNIFE.id()) {
                            yield it;
                        }
                        if (weapon == null) {
                            ItemDef def = defs.item(it.id());
                            if (def != null && def.isMeleeWeapon()) {
                                weapon = it;
                            }
                        }
                    }
                    yield weapon;
                }
            };
        }
    }

    public sealed interface Target {

        Stand defaultStand();

        /**
         * A scenery object anchored on {@code (x, y)}. {@code alsoIds} are other objects that may stand
         * on that tile instead and answer the same click, such as the stump of a cut jungle tree.
         */
        record Scenery(int objectId, int x, int y, List<Integer> alsoIds) implements Target {
            public Scenery {
                alsoIds = List.copyOf(alsoIds);
            }

            public Scenery(int objectId, int x, int y) {
                this(objectId, x, y, List.of());
            }

            public boolean matches(int id) {
                return id == objectId || alsoIds.contains(id);
            }

            @Override
            public Stand defaultStand() {
                return Stand.ON_ENTRY;
            }
        }

        /** A wall object on {@code (x, y)} facing {@code dir}; a negative {@code dir} matches any. */
        record Boundary(int boundaryId, int x, int y, int dir) implements Target {
            @Override
            public Stand defaultStand() {
                return Stand.IN_REACH;
            }
        }

        /**
         * The NPCs of these spawns, by server index; the nearest one that can be engaged is used.
         * {@code npcIds} are the ids those spawns have.
         */
        record Npcs(int[] serverIndexes, int[] npcIds) implements Target {
            @Override
            public Stand defaultStand() {
                return Stand.IN_VIEW;
            }
        }

        /** No world target: the action is on the player or their inventory, fired from anywhere. */
        record Self() implements Target {
            @Override
            public Stand defaultStand() {
                return Stand.IN_VIEW;
            }
        }

        Target SELF = new Self();
    }

    public EngageSpec {
        dialog = List.copyOf(dialog);
        if (stand == null) {
            stand = target.defaultStand();
        }
    }

    public EngageSpec(Action action, Target target, List<String[]> dialog, boolean awaitsDialog, Stand stand) {
        this(action, target, dialog, awaitsDialog, stand, false);
    }

    public static EngageSpec of(Action action, Target target) {
        return new EngageSpec(action, target, List.of(), false, null);
    }

    /** {@code action} on {@code target}, then each menu answered by the first group that matches it. */
    public static EngageSpec of(Action action, Target target, List<String[]> dialog) {
        return new EngageSpec(action, target, dialog, true, null);
    }

    /** {@code action} on {@code target}, then menu N answered by group N, for menus whose options repeat. */
    public static EngageSpec sequence(Action action, Target target, List<String[]> dialog) {
        return new EngageSpec(action, target, dialog, true, null, true);
    }

    public static EngageSpec bespoke(BespokeScript script) {
        return new EngageSpec(new Action.Bespoke(script), Target.SELF, List.of(), false, Stand.ON_ENTRY);
    }

    /** A one-line description for route listings, naming objects and items by id. */
    public String describe() {
        String verb =
                switch (action) {
                    case Action.Interact i -> i.command() == 2 ? "interact(2) with" : "interact with";
                    case Action.NpcCommand n -> "command(" + n.command() + ") on";
                    case Action.UseItem u -> "use " + describe(u.selector()) + " on";
                    case Action.ItemCommand ic -> "command (rub) " + describe(ic.selector()) + " —";
                    case Action.Cast c -> "cast spell " + c.spellId() + " at";
                    case Action.Bespoke b -> "run bespoke script " + b.script() + " for";
                };
        String what =
                switch (target) {
                    case Target.Scenery s ->
                        "scenery " + s.objectId() + (s.alsoIds().isEmpty() ? "" : " (or " + s.alsoIds() + ")") + " @("
                                + s.x() + "," + s.y() + ")";
                    case Target.Boundary b -> "boundary " + b.boundaryId() + " @(" + b.x() + "," + b.y() + ")";
                    case Target.Npcs n -> "npc " + Arrays.toString(n.npcIds());
                    case Target.Self s -> "self";
                };
        return verb + " " + what + (dialog.isEmpty() ? "" : " and answer " + dialog.size() + " menus");
    }

    private static String describe(ItemSelector s) {
        return switch (s) {
            case ItemSelector.Fixed f -> "item " + f.itemId();
            case ItemSelector.BestCutter c -> "best cutter";
        };
    }
}
