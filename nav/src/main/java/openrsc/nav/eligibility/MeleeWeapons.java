package openrsc.nav.eligibility;

import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ItemDef;

/**
 * Which items count as a weapon for cutting a web. Where webs are used on, the server takes a
 * main-hand item whose name has no "staff", "bow" or "cythe" ({@link ItemDef#isMeleeWeapon()});
 * where the world lets a player click a web, it takes any main-hand item, staves and bows included.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L30">CutWeb.onUseBound</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L70">CutWeb.onOpBound</a>
 */
public final class MeleeWeapons {

    private static final int WEAPON_SLOT = 4;

    private MeleeWeapons() {}

    /** Whether any unnoted item in {@code inventory}, worn or not, counts as a weapon. */
    public static boolean carried(Inventory inventory, ServerData defs) {
        for (InventorySlot s : inventory.slots()) {
            if (!s.noted() && isMelee(defs, s.item().id())) {
                return true;
            }
        }
        return false;
    }

    /** The id of the first banked item that counts as a weapon, or {@code -1} when none does. */
    public static int firstBanked(Bank bank, ServerData defs) {
        for (BankSlot s : bank.slots()) {
            if (isMelee(defs, s.item().id())) {
                return s.item().id();
            }
        }
        return -1;
    }

    /** Whether {@code itemId} counts as a weapon on {@code defs}' world; an unknown id does not. */
    public static boolean isMelee(ServerData defs, int itemId) {
        ItemDef def = defs.item(itemId);
        if (def == null) {
            return false;
        }
        return defs.world().rules().leftClickWebs() ? def.wearSlot() == WEAPON_SLOT : def.isMeleeWeapon();
    }
}
