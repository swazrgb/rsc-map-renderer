package openrsc.bot.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.packets.CastOnInventoryItemPacket;
import openrsc.bot.api.packets.DropPacket;
import openrsc.bot.api.packets.ItemCommandPacket;
import openrsc.bot.api.packets.ItemEquipPacket;
import openrsc.bot.api.packets.ItemUnequipPacket;
import openrsc.bot.api.packets.UseItemOnBoundaryPacket;
import openrsc.bot.api.packets.UseItemOnGroundItemPacket;
import openrsc.bot.api.packets.UseItemOnItemPacket;
import openrsc.bot.api.packets.UseItemOnNpcPacket;
import openrsc.bot.api.packets.UseItemOnObjectPacket;
import openrsc.bot.api.packets.UseItemOnPlayerPacket;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.ItemRef;
import openrsc.gamedata.data.RangeWeapons;
import openrsc.gamedata.defs.EquipmentTabItems;
import openrsc.gamedata.defs.ItemDef;
import org.jetbrains.annotations.Nullable;

public final class Worn {

    private final boolean equipmentTab;
    private final Inventory pack;
    private final List<WornItem> items;

    private Worn(boolean equipmentTab, Inventory pack, List<WornItem> items) {
        this.equipmentTab = equipmentTab;
        this.pack = pack;
        this.items = items;
    }

    public static Worn of(ServerData serverData, Inventory pack, List<WornItem> equipment) {
        boolean tab = serverData.world().rules().equipmentTab();
        if (tab) {
            return new Worn(true, pack, List.copyOf(asCarried(serverData, equipment)));
        }
        List<WornItem> out = null;
        for (InventorySlot s : pack.slots()) {
            if (!s.equipped()) {
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(4);
            }
            ItemDef def = serverData.item(s.id());
            out.add(new WornItem(def == null ? -1 : def.wearSlot(), s.id(), s.amount()));
        }
        return new Worn(false, pack, out == null ? List.of() : List.copyOf(out));
    }

    public static Inventory carried(ServerData serverData, Inventory pack, List<WornItem> equipment) {
        if (equipment.isEmpty()) {
            return pack;
        }
        List<WornItem> worn = asCarried(serverData, equipment);
        List<InventorySlot> slots = new ArrayList<>(pack.slots().size() + worn.size());
        slots.addAll(pack.slots());
        for (WornItem w : worn) {
            slots.add(new InventorySlot(slots.size(), w.catalogId(), w.amount(), true, false));
        }
        return Inventory.of(slots, true);
    }

    private static List<WornItem> asCarried(ServerData serverData, List<WornItem> equipment) {
        boolean formFitting = serverData.world().rules().formFittingChainmail();
        List<WornItem> out = new ArrayList<>(equipment.size());
        for (WornItem w : equipment) {
            int body = formFitting ? chainBody(w.catalogId()) : -1;
            out.add(body < 0 ? w : new WornItem(w.wieldPos(), body, w.amount()));
        }
        out.sort(Comparator.comparingInt(WornItem::wieldPos));
        return out;
    }

    private static final int[][] CHAIN_CUTS = {
        {ItemId.BRONZE_CHAIN_MAIL_BODY.id(), ItemId.BRONZE_CHAIN_MAIL_TOP.id()},
        {ItemId.IRON_CHAIN_MAIL_BODY.id(), ItemId.IRON_CHAIN_MAIL_TOP.id()},
        {ItemId.STEEL_CHAIN_MAIL_BODY.id(), ItemId.STEEL_CHAIN_MAIL_TOP.id()},
        {ItemId.BLACK_CHAIN_MAIL_BODY.id(), ItemId.BLACK_CHAIN_MAIL_TOP.id()},
        {ItemId.MITHRIL_CHAIN_MAIL_BODY.id(), ItemId.MITHRIL_CHAIN_MAIL_TOP.id()},
        {ItemId.ADAMANTITE_CHAIN_MAIL_BODY.id(), ItemId.ADAMANTITE_CHAIN_MAIL_TOP.id()},
        {ItemId.RUNE_CHAIN_MAIL_BODY.id(), ItemId.RUNE_CHAIN_MAIL_TOP.id()},
        {ItemId.DRAGON_SCALE_MAIL.id(), ItemId.DRAGON_SCALE_MAIL_TOP.id()},
    };

    public static int chainTwin(int itemId) {
        for (int[] pair : CHAIN_CUTS) {
            if (pair[0] == itemId) {
                return pair[1];
            }
            if (pair[1] == itemId) {
                return pair[0];
            }
        }
        return -1;
    }

    private static int chainBody(int itemId) {
        for (int[] pair : CHAIN_CUTS) {
            if (pair[1] == itemId) {
                return pair[0];
            }
        }
        return -1;
    }

    public static int packSlots(AccountFacts account) {
        if (account.serverData().world().rules().equipmentTab()) {
            return 0;
        }
        int n = 0;
        for (InventorySlot s : account.inventory().slots()) {
            if (s.equipped()) {
                n++;
            }
        }
        return n;
    }

    public static long bandSlots(ServerData defs, int itemId, long target, boolean worn) {
        if (target <= 0) {
            return 0;
        }
        long slots = defs.isStackable(itemId) ? 1 : target;
        return worn && defs.world().rules().equipmentTab() ? slots - 1 : slots;
    }

    public boolean equipmentTab() {
        return equipmentTab;
    }

    public Inventory pack() {
        return pack;
    }

    public boolean inPack(InventorySlot slot) {
        return !equipmentTab || !slot.equipped();
    }

    public List<WornItem> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean has(int itemId) {
        for (WornItem w : items) {
            if (w.catalogId() == itemId) {
                return true;
            }
        }
        return false;
    }

    public boolean has(ItemRef item) {
        return has(item.id());
    }

    public long count(int itemId) {
        long n = 0;
        for (WornItem w : items) {
            if (w.catalogId() == itemId) {
                n += w.amount();
            }
        }
        return n;
    }

    public @Nullable WornItem of(int itemId) {
        for (WornItem w : items) {
            if (w.catalogId() == itemId) {
                return w;
            }
        }
        return null;
    }

    public @Nullable WornItem inSlot(int wieldPos) {
        for (WornItem w : items) {
            if (w.wieldPos() == wieldPos) {
                return w;
            }
        }
        return null;
    }

    public @Nullable WornItem rangeWeapon() {
        for (WornItem w : items) {
            if (RangeWeapons.isRangeWeapon(w.catalogId())) {
                return w;
            }
        }
        return null;
    }

    public @Nullable WornItem ammo() {
        return inSlot(EquipmentTabItems.AMMO_SLOT);
    }

    public static int highestPackSlot(Packet packet) {
        return switch (packet) {
            case ItemCommandPacket p -> p.slot();
            case ItemEquipPacket p -> p.slot();
            case ItemUnequipPacket p -> p.slot();
            case DropPacket p -> p.slot();
            case UseItemOnItemPacket p -> Math.max(p.slot(), p.targetSlot());
            case UseItemOnNpcPacket p -> p.slot();
            case UseItemOnPlayerPacket p -> p.slot();
            case UseItemOnObjectPacket p -> p.slot();
            case UseItemOnBoundaryPacket p -> p.slot();
            case UseItemOnGroundItemPacket p -> p.slot();
            case CastOnInventoryItemPacket p -> p.slot();
            default -> -1;
        };
    }

    @Override
    public String toString() {
        return "Worn" + items;
    }
}
