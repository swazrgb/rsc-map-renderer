package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableInventory;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PacksTest {

    private static final int LOBSTER = ItemId.LOBSTER.id();
    private static final int COINS = ItemId.COINS.id();
    private static final int SWORD = ItemId.DRAGON_SWORD.id();
    private static final int AMULET = ItemId.GLARIALS_AMULET.id();

    private static ServerData env;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
    }

    private static Inventory pack(InventorySlot... slots) {
        MutableInventory m = new MutableInventory();
        for (InventorySlot s : slots) {
            m.setSlot(s.slot(), s);
        }
        return m.snapshot();
    }

    @Test
    @DisplayName("a stop moves each item to its target and puts the equips on")
    void aStopMovesToTargetsAndPutsOn() {
        Inventory arriving = pack(new InventorySlot(0, LOBSTER, 1, false), new InventorySlot(1, COINS, 50, false));
        Manifest stop = new Manifest(
                Map.of(ItemId.COINS, 200L, ItemId.DRAGON_SWORD, 1L),
                Map.of(ItemId.LOBSTER, 0L),
                Set.of(ItemId.DRAGON_SWORD));
        Inventory leaving = Packs.apply(arriving, stop, env);
        assertEquals(200, leaving.count(COINS), "coins raised to their target");
        assertEquals(1, leaving.count(SWORD), "the sword granted");
        assertEquals(0, leaving.count(LOBSTER), "the lobster deposited");
        assertTrue(leaving.isEquipped(SWORD), "and the sword worn: " + leaving.slots());
        assertFalse(leaving.isEquipped(COINS));
        assertEquals(2, leaving.usedSlots(), "one stack, one sword: " + leaving.slots());
        assertEquals(1, leaving.slots().stream().filter(InventorySlot::equipped).count());
        assertSame(arriving, Packs.apply(arriving, Manifest.EMPTY, env), "nothing moved: the pack");
    }

    @Test
    @DisplayName("a fare shrinks a stack and drops an exhausted one")
    void aFareShrinksAStackAndDropsAnExhaustedOne() {
        Inventory boarding = pack(new InventorySlot(0, COINS, 30, false), new InventorySlot(1, LOBSTER, 1, false));
        Inventory sailed = Packs.consume(boarding, Map.of(COINS, 30L), env);
        assertEquals(0, sailed.count(COINS));
        assertEquals(1, sailed.usedSlots(), "the coin slot is gone: " + sailed.slots());
        assertEquals(LOBSTER, sailed.slots().get(0).item().id());
        Inventory partly = Packs.consume(boarding, Map.of(COINS, 10L), env);
        assertEquals(20, partly.count(COINS));
        assertEquals(2, partly.usedSlots());
        assertSame(boarding, Packs.consume(boarding, Map.of(), env), "no fare: the pack");
    }

    @Test
    @DisplayName("a gate goes on the body once and stays on past the hop")
    void aGateGoesOnTheBodyOnceAndStaysOn() {
        Inventory carrying = pack(new InventorySlot(0, AMULET, 1, false), new InventorySlot(1, LOBSTER, 1, false));
        Inventory wearing = Packs.wear(carrying, Set.of(AMULET), env);
        assertTrue(wearing.isEquipped(AMULET));
        assertEquals(1, wearing.equippedCount(AMULET));
        assertTrue(wearing.slots().get(0).equipped(), "the amulet's slot reads worn");
        assertFalse(wearing.slots().get(1).equipped());
        assertSame(wearing, Packs.wear(wearing, Set.of(AMULET), env), "already on: the pack");
        assertSame(carrying, Packs.wear(carrying, Set.of(SWORD), env), "not carried: the pack");
        Inventory after = Packs.consume(wearing, Map.of(LOBSTER, 1L), env);
        assertTrue(after.isEquipped(AMULET), "the fare after the hop leaves it on");
        assertEquals(1, after.usedSlots());
    }

    @Test
    @DisplayName("a deposit takes a worn item off the body with it")
    void aDepositTakesAWornItemOffTheBody() {
        Inventory armed = pack(new InventorySlot(0, SWORD, 1, true), new InventorySlot(1, COINS, 10, false));
        Manifest shed = new Manifest(Map.of(), Map.of(ItemId.DRAGON_SWORD, 0L), Set.of());
        Inventory bare = Packs.apply(armed, shed, env);
        assertEquals(0, bare.count(SWORD));
        assertFalse(bare.isEquipped(SWORD));
        assertEquals(0, bare.equippedCount(SWORD));
        assertEquals(1, bare.usedSlots());
    }

    @Test
    @DisplayName("a view compares by content with a frozen pack, and with its re-derivation")
    void viewsCompareByContent() {
        Inventory carrying = pack(new InventorySlot(0, AMULET, 1, false), new InventorySlot(1, LOBSTER, 1, false));
        Inventory worn = Packs.wear(carrying, Set.of(AMULET), env);
        Inventory frozen = pack(new InventorySlot(0, AMULET, 1, true), new InventorySlot(1, LOBSTER, 1, false));
        assertEquals(frozen, worn);
        assertEquals(worn, frozen);
        assertEquals(frozen.hashCode(), worn.hashCode());
        assertNotEquals(carrying, worn);
        Inventory again = Packs.wear(
                pack(new InventorySlot(0, AMULET, 1, false), new InventorySlot(1, LOBSTER, 1, false)),
                Set.of(AMULET),
                env);
        assertEquals(worn, again, "the same fold over an equal pack is the same step");
        Inventory paid = Packs.consume(worn, Map.of(LOBSTER, 1L), env);
        assertEquals(pack(new InventorySlot(0, AMULET, 1, true)), paid);
    }
}
