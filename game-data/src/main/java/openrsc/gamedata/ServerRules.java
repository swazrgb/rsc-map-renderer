package openrsc.gamedata;

import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.defs.NpcDef;

/**
 * The game rules a world's server runs by, read from its {@code .conf}. Each component is what the
 * server's configuration makes of the key it names: the server's default for an absent key, and
 * the server's own derivation where it combines keys. Switches named only by their key are on when
 * the key is {@code true}.
 *
 * @param npcBlocking {@code npc_blocking}
 * @param maxPvpMeleeDistance {@code max_pvp_melee_attack_distance}: when attacker and target are
 *     both walking, the farthest apart, in truncated Pythagorean tiles, that the server accepts a
 *     melee attack on a player; farther, it stops the attacker's walk instead
 * @param fatigue {@code want_fatigue}
 * @param sleeping {@code features_sleep}
 * @param equipmentTab {@code want_equipment_tab}
 * @param batching {@code batch_progression}
 * @param bankCapacity bank slots, from {@code member_world} and {@code want_custom_banks}
 * @param customBanks {@code want_custom_banks}
 * @param quickBank {@code right_click_bank}
 * @param bankPins {@code want_bank_pins}
 * @param bankNotes {@code want_bank_notes}
 * @param bankPresets {@code want_bank_presets}
 * @param certDeposit {@code want_cert_deposit}
 * @param certerBankExchange {@code want_certer_bank_exchange}
 * @param dropX {@code want_drop_x}
 * @param quickTrade {@code right_click_trade}
 * @param decanting {@code want_decanting}
 * @param postQuestRegionsOpen {@code locked_post_quest_regions_accessible}
 * @param postQuestItemsReclaimable {@code can_retrieve_post_quest_items}
 * @param wormbrainBarter {@code want_barter_wormbrains}
 * @param gameTickMs {@code game_tick}, in ms
 * @param npcRespawnMultiplier {@code npc_respawn_multiplier}: an NPC respawns
 *     {@code respawnTime * multiplier} seconds after it dies, rounded up to whole ticks
 * @param maxItemId {@code restrict_item_id}: the highest item id the world yields, or
 *     {@link #UNRESTRICTED}
 * @param maxSceneryId {@code restrict_scenery_id}, as {@code maxItemId}
 * @param combatExpRate {@code combat_exp_rate}
 * @param skillingExpRate {@code skilling_exp_rate}
 * @param fishingSpotsDeplete {@code fishing_spots_depletable}
 * @param extendedMiningRocks {@code mining_rocks_extended}
 * @param npcsDontRetreat {@code npc_dont_retreat}
 * @param leftClickWebs {@code want_leftclick_webs}
 * @param noLevelRequirementWield {@code no_level_requirement_wield}
 * @param equipQuestItemsWithoutQuests {@code equip_quest_items_without_quests}
 * @param strictCheckAll {@code strict_check_all}: when off, the three keys below decide whether a
 *     poisoned throwing dart, throwing knife or spear skips its wield requirement
 * @param strictPoisonedDartCheck {@code strict_pdart_check}
 * @param strictPoisonedKnifeCheck {@code strict_pknife_check}
 * @param strictPoisonedSpearCheck {@code strict_pspear_check}
 * @param formFittingChainmail {@code form_fitting_chainmail}, which the server applies only with
 *     {@code custom_sprites} on
 * @param combatOdyssey {@code want_combat_odyssey}
 * @param kbdRareDropTable whether a King Black Dragon kill also rolls the custom rare table: the
 *     server rolls it with {@code custom_sprites} on and {@code want_openpk_points} off, and builds it
 *     only with {@code want_custom_quests}
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L376">ServerConfiguration.initConfig</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L147">World.World</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/AttackHandler.java#L103">AttackHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/Npc.java#L819">Npc.remove</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/DelayedEvent.java#L16">DelayedEvent.DelayedEvent</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L950">World.canYield</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L624">Equipment.ableToEquip</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L300">Equipment.equipItem</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/Npc.java#L390">Npc.dropItems</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/NpcDrops.java#L62">NpcDrops.load</a>
 */
public record ServerRules(
        NpcBlocking npcBlocking,
        int maxPvpMeleeDistance,
        boolean fatigue,
        boolean sleeping,
        boolean equipmentTab,
        boolean batching,
        int bankCapacity,
        boolean customBanks,
        boolean quickBank,
        boolean bankPins,
        boolean bankNotes,
        boolean bankPresets,
        boolean certDeposit,
        boolean certerBankExchange,
        boolean dropX,
        boolean quickTrade,
        boolean decanting,
        boolean postQuestRegionsOpen,
        boolean postQuestItemsReclaimable,
        boolean wormbrainBarter,
        int gameTickMs,
        double npcRespawnMultiplier,
        int maxItemId,
        int maxSceneryId,
        double combatExpRate,
        double skillingExpRate,
        boolean fishingSpotsDeplete,
        boolean extendedMiningRocks,
        boolean npcsDontRetreat,
        boolean leftClickWebs,
        boolean noLevelRequirementWield,
        boolean equipQuestItemsWithoutQuests,
        boolean strictCheckAll,
        boolean strictPoisonedDartCheck,
        boolean strictPoisonedKnifeCheck,
        boolean strictPoisonedSpearCheck,
        boolean formFittingChainmail,
        boolean combatOdyssey,
        boolean kbdRareDropTable) {

    /** Bank slots on a members world without {@code want_custom_banks}. */
    public static final int MEMBERS_BANK_CAPACITY = 192;

    /** Bank slots on a free-to-play world, with or without {@code want_custom_banks}. */
    public static final int F2P_BANK_CAPACITY = 48;

    /** Bank slots on a members world with {@code want_custom_banks}: the server's {@code ItemId.maxCustom}. */
    public static final int CUSTOM_BANK_CAPACITY = 1592;

    /** The {@code maxItemId} or {@code maxSceneryId} of a world that sets no ceiling. */
    public static final int UNRESTRICTED = -1;

    private static final ServerRules SERVER_DEFAULTS = from(ServerProperties.parse(List.of()));

    // The keys uranium.conf sets to other than the server's defaults; ServerRulesTest holds this to
    // the conf itself.
    private static final List<String> URANIUM_OVERRIDES = List.of(
            "strict_check_all: false",
            "strict_pdart_check: false",
            "strict_pknife_check: false",
            "strict_pspear_check: false");

    private static final ServerRules URANIUM = from(ServerProperties.parse(URANIUM_OVERRIDES));

    /**
     * Which live NPCs a player cannot step onto, by {@code npc_blocking} mode in declaration order
     * (0 to 4). "Aggressive" means the server's {@code isAggressive}: attackable and aggressive.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L658">PathValidation.isMobBlocking</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/NPCDef.java#L191">NPCDef.isAggressive</a>
     */
    public enum NpcBlocking {
        NONE,
        /** An aggressive NPC blocks a player whose combat level is below twice the NPC's, plus one. */
        AGGRESSIVE_TO_LEVEL,
        AGGRESSIVE,
        ATTACKABLE,
        ALL;

        /** The mode {@code mode} selects; {@link #NONE} for a mode the server does not know, as it does. */
        public static NpcBlocking ofMode(int mode) {
            return mode >= 0 && mode < values().length ? values()[mode] : NONE;
        }

        /**
         * Whether a live NPC of {@code def} blocks a player of {@code combatLevel}. A {@code null}
         * def blocks only under {@link #ALL}. Only {@link #AGGRESSIVE_TO_LEVEL} reads the level, and
         * counts one {@code <= 0} (unknown) as below every NPC's.
         */
        public boolean blocks(@Nullable NpcDef def, int combatLevel) {
            return switch (this) {
                case NONE -> false;
                case AGGRESSIVE_TO_LEVEL ->
                    def != null
                            && def.aggressive()
                            && def.attackable()
                            && (combatLevel <= 0 || combatLevel < def.combatLevel() * 2 + 1);
                case AGGRESSIVE -> def != null && def.aggressive() && def.attackable();
                case ATTACKABLE -> def != null && def.attackable();
                case ALL -> true;
            };
        }

        /** Whether an NPC of {@code def} blocks a player of some level. */
        public boolean mayBlock(@Nullable NpcDef def) {
            return blocks(def, 0);
        }
    }

    /** The rules of a conf that sets none of the keys: the server's defaults. */
    public static ServerRules serverDefaults() {
        return SERVER_DEFAULTS;
    }

    /** The rules {@code uranium.conf} sets, without reading it: the defaults with its strict checks off. */
    public static ServerRules uranium() {
        return URANIUM;
    }

    /**
     * The rules {@code conf} sets. A malformed value falls back to the default, where the server
     * refuses to start.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L833">ServerConfiguration.tryReadBool</a>
     */
    public static ServerRules from(ServerProperties conf) {
        boolean memberWorld = conf.bool("member_world", true);
        boolean customBanks = conf.bool("want_custom_banks", false);
        return new ServerRules(
                NpcBlocking.ofMode(conf.integer("npc_blocking", 2)),
                conf.integer("max_pvp_melee_attack_distance", 2),
                conf.bool("want_fatigue", true),
                conf.bool("features_sleep", true),
                conf.bool("want_equipment_tab", false),
                conf.bool("batch_progression", false),
                memberWorld ? (customBanks ? CUSTOM_BANK_CAPACITY : MEMBERS_BANK_CAPACITY) : F2P_BANK_CAPACITY,
                customBanks,
                conf.bool("right_click_bank", false),
                conf.bool("want_bank_pins", false),
                conf.bool("want_bank_notes", false),
                conf.bool("want_bank_presets", false),
                conf.bool("want_cert_deposit", false),
                conf.bool("want_certer_bank_exchange", false),
                conf.bool("want_drop_x", false),
                conf.bool("right_click_trade", false),
                conf.bool("want_decanting", false),
                conf.bool("locked_post_quest_regions_accessible", false),
                conf.bool("can_retrieve_post_quest_items", false),
                conf.bool("want_barter_wormbrains", false),
                conf.integer("game_tick", 640),
                conf.decimal("npc_respawn_multiplier", 1.0),
                ceiling(conf.integer("restrict_item_id", 1289)),
                ceiling(conf.integer("restrict_scenery_id", 1188)),
                conf.decimal("combat_exp_rate", 1.0),
                conf.decimal("skilling_exp_rate", 1.0),
                conf.bool("fishing_spots_depletable", false),
                conf.bool("mining_rocks_extended", false),
                conf.bool("npc_dont_retreat", false),
                conf.bool("want_leftclick_webs", false),
                conf.bool("no_level_requirement_wield", false),
                conf.bool("equip_quest_items_without_quests", false),
                conf.bool("strict_check_all", true),
                conf.bool("strict_pdart_check", true),
                conf.bool("strict_pknife_check", true),
                conf.bool("strict_pspear_check", true),
                conf.bool("custom_sprites", false) && conf.bool("form_fitting_chainmail", false),
                conf.bool("want_combat_odyssey", false),
                conf.bool("custom_sprites", false)
                        && !conf.bool("want_openpk_points", false)
                        && conf.bool("want_custom_quests", false));
    }

    // The server applies a ceiling only when it is >= 0.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L950
    private static int ceiling(int value) {
        return value < 0 ? UNRESTRICTED : value;
    }
}
