package openrsc.gamedata.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What an NPC drops on every kill besides its bones, copied from the drop tables the server builds on
 * every world: the items of weight 0 in each NPC's table. The tables a world adds with
 * {@code want_custom_quests} are left out. The items are as the server drops them at
 * {@code based_config_data} 50 and above, and before the world's {@code restrict_item_id} ceiling.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/NpcDrops.java#L358">NpcDrops.generateNpcDrops</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/NpcDrops.java#L62">NpcDrops.load</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/DropTable.java#L212">DropTable.invariableItems</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/Npc.java#L429">Npc.dropItems</a>
 */
public final class AlwaysDrops {

    private AlwaysDrops() {}

    /** {@code amount} of {@code item} on the ground. */
    public record Drop(ItemId item, int amount) {}

    private static final Map<Integer, List<Drop>> BY_NPC = byNpc();

    private static Map<Integer, List<Drop>> byNpc() {
        Map<Integer, List<Drop>> m = new HashMap<>();
        m.put(NpcId.UNICORN.id(), List.of(new Drop(ItemId.UNICORN_HORN, 1)));
        m.put(NpcId.CHICKEN.id(), List.of(new Drop(ItemId.RAW_CHICKEN, 1)));
        m.put(NpcId.COW_ATTACKABLE.id(), List.of(new Drop(ItemId.COW_HIDE, 1), new Drop(ItemId.RAW_BEEF, 1)));
        List<Drop> bear = List.of(new Drop(ItemId.RAW_BEAR_MEAT, 1), new Drop(ItemId.FUR, 1));
        m.put(NpcId.BEAR_LVL24.id(), bear);
        m.put(NpcId.BEAR.id(), bear);
        m.put(NpcId.BEAR_LVL26.id(), bear);
        List<Drop> rat = List.of(new Drop(ItemId.RAW_RAT_MEAT, 1));
        m.put(NpcId.RAT_LVL8.id(), rat);
        m.put(NpcId.RAT_LVL13.id(), rat);
        m.put(NpcId.DUNGEON_RAT.id(), rat);
        m.put(NpcId.RAT_WMAZEKEY.id(), List.of(new Drop(ItemId.RAW_RAT_MEAT, 1), new Drop(ItemId.RED_KEY, 1)));
        m.put(NpcId.LESSER_DEMON_WMAZEKEY.id(), List.of(new Drop(ItemId.BLACK_KEY, 1)));
        m.put(NpcId.JONNY_THE_BEARD.id(), List.of(new Drop(ItemId.SCROLL, 1)));
        m.put(NpcId.SKELETON_WMAZEKEY.id(), List.of(new Drop(ItemId.YELLOW_KEY, 1)));
        m.put(NpcId.MELZAR_THE_MAD.id(), List.of(new Drop(ItemId.MAGENTA_KEY, 1)));
        m.put(NpcId.JAILER.id(), List.of(new Drop(ItemId.JAIL_KEYS, 1)));
        m.put(NpcId.ZOMBIE_WMAZEKEY.id(), List.of(new Drop(ItemId.BLUE_KEY, 1)));
        m.put(NpcId.WIZARD.id(), List.of(new Drop(ItemId.WIZARDS_ROBE, 1), new Drop(ItemId.BLUE_WIZARDSHAT, 1)));
        m.put(NpcId.HIGHWAYMAN.id(), List.of(new Drop(ItemId.BLACK_CAPE, 1)));
        m.put(NpcId.MONK_OF_ZAMORAK_MACE.id(), List.of(new Drop(ItemId.STEEL_MACE, 1)));
        m.put(NpcId.ICE_QUEEN.id(), List.of(new Drop(ItemId.ICE_GLOVES, 1)));
        m.put(NpcId.GHOST_WMAZEKEY.id(), List.of(new Drop(ItemId.ORANGE_KEY, 1)));
        m.put(NpcId.GREY_WOLF.id(), List.of(new Drop(ItemId.GREY_WOLF_FUR, 1)));
        m.put(NpcId.FIREBIRD.id(), List.of(new Drop(ItemId.RED_FIREBIRD_FEATHER, 1)));
        m.put(NpcId.ANIMATED_AXE.id(), List.of(new Drop(ItemId.IRON_BATTLE_AXE, 1)));
        m.put(NpcId.BLACK_UNICORN.id(), List.of(new Drop(ItemId.UNICORN_HORN, 1)));
        m.put(NpcId.OTHERWORLDLY_BEING.id(), List.of(new Drop(ItemId.COINS, 2)));
        m.put(NpcId.FIRST_PLAGUE_SHEEP.id(), List.of(new Drop(ItemId.PLAGUED_SHEEP_REMAINS_1, 1)));
        m.put(NpcId.SECOND_PLAGUE_SHEEP.id(), List.of(new Drop(ItemId.PLAGUED_SHEEP_REMAINS_2, 1)));
        m.put(NpcId.THIRD_PLAGUE_SHEEP.id(), List.of(new Drop(ItemId.PLAGUED_SHEEP_REMAINS_3, 1)));
        m.put(NpcId.FOURTH_PLAGUE_SHEEP.id(), List.of(new Drop(ItemId.PLAGUED_SHEEP_REMAINS_4, 1)));
        m.put(NpcId.UGTHANKI.id(), List.of(new Drop(ItemId.RAW_UGTHANKI_MEAT, 1)));
        m.put(NpcId.OOMLIE_BIRD.id(), List.of(new Drop(ItemId.RAW_OOMLIE_MEAT, 1)));
        return Map.copyOf(m);
    }

    /** What NPC {@code npcId} drops on every kill; empty for an NPC whose table has no such drop. */
    public static List<Drop> of(int npcId) {
        return BY_NPC.getOrDefault(npcId, List.of());
    }

    static Map<Integer, List<Drop>> all() {
        return BY_NPC;
    }

    /** The NPCs, by id, that drop {@code item} on every kill. */
    public static Set<Integer> droppers(ItemRef item) {
        return BY_NPC.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(d -> d.item().id() == item.id()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
