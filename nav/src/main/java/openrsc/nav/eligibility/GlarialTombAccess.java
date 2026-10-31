package openrsc.nav.eligibility;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;

/**
 * What Glarial's tomb refuses: using the pebble on the gravestone opens it only while the inventory
 * holds no item whose lower-cased name contains one of a list of weapon and armour words, or the
 * rune skirt. The test is on names, so it also refuses bowls ("bow") and pickaxes ("axe") but lets
 * arrows and darts through; swordfish is exempt from "sword".
 *
 * <p>The server reads the inventory only: on a world with an equipment tab a worn item is not in it.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L970">Waterfall_Quest.CANT_GO</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/Waterfall_Quest.java#L730">Waterfall_Quest.onUseLoc</a>
 */
public final class GlarialTombAccess {

    private static final String[] BANNED_FRAGMENTS = {
        "dagger",
        "scimitar",
        "bow",
        "mail",
        "plated",
        "shield",
        "sword",
        "mace",
        "helmet",
        "axe",
        "throwing knife",
        "spear",
    };

    private static final String[] SWORD_EXEMPT = {"swordfish", "burnt swordfish", "raw swordfish"};

    private static final Map<ServerData, Map<Integer, Boolean>> CACHE = new ConcurrentHashMap<>();

    private GlarialTombAccess() {}

    /**
     * Whether the tomb refuses {@code itemId} under {@code sd}'s item names; an unknown id is let
     * through. Answers are memoised per {@code sd}; safe to call from any thread.
     */
    public static boolean isBanned(ServerData sd, int itemId) {
        if (itemId == ItemId.RUNE_SKIRT.id()) {
            return true;
        }
        return CACHE.computeIfAbsent(sd, k -> new ConcurrentHashMap<>()).computeIfAbsent(itemId, id -> {
            ItemDef def = sd.item(id);
            if (def == null || def.name() == null) {
                return false;
            }
            String n = def.name().toLowerCase(Locale.ROOT);
            for (String frag : BANNED_FRAGMENTS) {
                if (!n.contains(frag)) {
                    continue;
                }
                if (frag.equals("sword") && isSwordExempt(n)) {
                    continue;
                }
                return true;
            }
            return false;
        });
    }

    /** Whether anything in the pack is refused; worn items count unless the world has an equipment tab. */
    public static boolean carriesBannedGear(AccountFacts state, ServerData sd) {
        for (InventorySlot it : state.inventory().slots()) {
            if (state.inventory().takesPackSlot(it) && isBanned(sd, it.id())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSwordExempt(String lowerName) {
        for (String exempt : SWORD_EXEMPT) {
            if (lowerName.equals(exempt)) {
                return true;
            }
        }
        return false;
    }
}
