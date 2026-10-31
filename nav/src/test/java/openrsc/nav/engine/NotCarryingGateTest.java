package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import org.junit.jupiter.api.Test;

/** A crossing refused while the pack carries an item: a stop on the way banks the item. */
class NotCarryingGateTest {

    private static final Point EDGEVILLE_BANK = Point.of(220, 447);
    private static final Rect PAST_VARROCK = Rect.of(109, 525, 109, 525);
    private static final int RUM = ItemId.KARAMJA_RUM.id();
    private static final Point PORT_SARIM_DOCK = Point.of(264, 660);
    private static final Point ENTRANA = Point.of(404, 532);
    private static final int SWORD = ItemId.ADAMANTITE_2_HANDED_SWORD.id();

    @Test
    void a_stop_banks_the_rum_the_teleport_refuses_and_casts_it() {
        List<InventorySlot> slots = new ArrayList<>();
        slots.add(new InventorySlot(0, RUM, 1, false));
        for (int i = 0; i < 3; i++) {
            slots.add(new InventorySlot(slots.size(), ItemId.LOGS.id(), 1, false));
        }
        Bank bank = Bank.of(List.of(
                new BankSlot(0, ItemId.LAW_RUNE, 100),
                new BankSlot(1, ItemId.AIR_RUNE, 100),
                new BankSlot(2, ItemId.FIRE_RUNE, 100)));
        LoadoutSpec logs = new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.LOGS, 0, 3, 3, false)));
        NavPolicy spells = new NavPolicy(NavPolicy.DangerAvoidance.ON, true, false, Map.of(), false);

        Route route = assertInstanceOf(
                        PlanResult.Planned.class,
                        RealDefs.pathfinder()
                                .nav()
                                .plan(new PlanRequest(
                                        account(slots, bank), EDGEVILLE_BANK, PAST_VARROCK, logs, spells)))
                .route();
        Route.Hop cast = null;
        Route.BankStop stop = null;
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.BankStop b && cast == null) {
                stop = b;
            }
            if (s instanceof Route.Hop h && h.transport().equals("VARROCK_TELEPORT")) {
                cast = h;
            }
        }
        assertNotNull(cast, "the teleport: " + route);
        assertNotNull(stop, "a stop before it: " + route);
        assertEquals(0, stop.leaving().count(RUM), "the rum banked: " + route);
        assertEquals(1, cast.pack().count(ItemId.LAW_RUNE.id()), route.toString());
        assertEquals(
                3, route.steps().getLast() instanceof Route.Arrive a ? a.pack().count(ItemId.LOGS.id()) : -1);
    }

    @Test
    void the_notes_of_a_weapon_the_monks_refuse_are_banked_before_the_boat() {
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        assertTrue(coleslaw.ridesAsNotes(SWORD), "the premise: the sword comes out of a bank as notes");
        List<InventorySlot> slots =
                List.of(new InventorySlot(0, SWORD, 3, false, true), new InventorySlot(1, ItemId.SHARK.id(), 1, false));
        MutableInventory inv = new MutableInventory(coleslaw.world().rules().equipmentTab());
        inv.setAll(slots);
        AccountFacts account = new AccountFacts.Snapshot(
                coleslaw, inv.snapshot(), Bank.of(List.of()), stats(), new MutableQuests().snapshot(), PORT_SARIM_DOCK);
        NavPolicy calm = new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);

        Route route = assertInstanceOf(
                        PlanResult.Planned.class,
                        RealDefs.pathfinder(RealDefs.COLESLAW)
                                .nav()
                                .plan(new PlanRequest(account, PORT_SARIM_DOCK, ENTRANA, LoadoutSpec.any(), calm)))
                .route();
        Route.BankStop stop = null;
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.BankStop b) {
                stop = b;
            }
        }
        assertNotNull(stop, "a stop banks the notes: " + route);
        assertEquals(0, stop.leaving().count(NoteForm.BOTH, SWORD), route.toString());
        assertEquals(1, stop.leaving().count(ItemId.SHARK.id()), "the shark rides along: " + route);
    }

    private static Stats stats() {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        return stats.snapshot();
    }

    private static AccountFacts account(List<InventorySlot> slots, Bank bank) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        MutableInventory inv = new MutableInventory();
        inv.setAll(slots);
        return new AccountFacts.Snapshot(
                RealDefs.serverData(),
                inv.snapshot(),
                bank,
                stats.snapshot(),
                new MutableQuests().snapshot(),
                EDGEVILLE_BANK);
    }
}
