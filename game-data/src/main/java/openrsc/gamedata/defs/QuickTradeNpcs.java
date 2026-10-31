package openrsc.gamedata.defs;

import java.util.Set;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.NpcId;

/**
 * The shopkeepers whose "Trade" command opens the shop without a word, on a world with
 * {@code right_click_trade}. The server writes the command into their defs as it loads, so the
 * JSON defs do not carry it.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L346">EntityHandler.customNpcConditions</a>
 */
public final class QuickTradeNpcs {

    private QuickTradeNpcs() {}

    // The server's list, less the two Gu'Tanoth ogre traders and the dwarven smithy: no plugin answers
    // their "Trade".
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L77
    private static final Set<NpcId> COMMAND1 = Set.of(
            NpcId.BOB,
            NpcId.HORVIK_THE_ARMOURER,
            NpcId.SHOPKEEPER_VARROCK,
            NpcId.SHOPKEEPER_LUMBRIDGE,
            NpcId.SHOPKEEPER_VARROCK_SWORD,
            NpcId.LOWE,
            NpcId.THESSALIA,
            NpcId.ZAFF,
            NpcId.PEKSA,
            NpcId.SHOP_ASSISTANT_VARROCK,
            NpcId.SHOP_ASSISTANT_LUMBRIDGE,
            NpcId.ZEKE,
            NpcId.LOUIE_LEGS,
            NpcId.SHOPKEEPER_ALKHARID,
            NpcId.SHOP_ASSISTANT_ALKHARID,
            NpcId.CASSIE,
            NpcId.RANAEL,
            NpcId.SHOPKEEPER_FALADOR,
            NpcId.SHOP_ASSISTANT_FALADOR,
            NpcId.VALAINE,
            NpcId.DROGO,
            NpcId.FLYNN,
            NpcId.WYDIN,
            NpcId.SHOP_ASSISTANT_VARROCK_SWORD,
            NpcId.BRIAN,
            NpcId.WAYNE,
            NpcId.DWARVEN_SHOPKEEPER,
            NpcId.SHOPKEEPER_RIMMINGTON,
            NpcId.SHOP_ASSISTANT_RIMMINGTON,
            NpcId.BETTY,
            NpcId.HERQUIN,
            NpcId.ROMMIK,
            NpcId.GRUM,
            NpcId.ZAMBO,
            NpcId.GERRANT,
            NpcId.SHOPKEEPER_KARAMJA,
            NpcId.SHOP_ASSISTANT_KARAMJA,
            NpcId.DOMMIK,
            NpcId.SCAVVO,
            NpcId.SHOPKEEPER_EDGEVILLE,
            NpcId.SHOP_ASSISTANT_EDGEVILLE,
            NpcId.OZIACH,
            NpcId.IRKSOL,
            NpcId.JAKUT,
            NpcId.FAIRY_SHOPKEEPER,
            NpcId.FAIRY_SHOP_ASSISTANT,
            NpcId.GAIUS,
            NpcId.JATIX,
            NpcId.NOTERAZZO,
            NpcId.FAT_TONY,
            NpcId.HARRY,
            NpcId.ALFONSE_THE_WAITER,
            NpcId.HELEMOS,
            NpcId.DAVON,
            NpcId.ARHEIN,
            NpcId.CANDLEMAKER,
            NpcId.HICKTON,
            NpcId.FRINCOS,
            NpcId.GEM_TRADER,
            NpcId.BAKER,
            NpcId.FUR_TRADER,
            NpcId.SILVER_MERCHANT,
            NpcId.SPICE_MERCHANT,
            NpcId.GEM_MERCHANT,
            NpcId.ZENESHA,
            NpcId.AEMAD,
            NpcId.KORTAN,
            NpcId.SHOPKEEPER_FISHING_GUILD,
            NpcId.SHOPKEEPER_PORTKHAZARD,
            NpcId.BOLKOY,
            NpcId.TAILOR,
            NpcId.MAGIC_STORE_OWNER,
            NpcId.JIMINUA,
            NpcId.SHOP_KEEPER_TRAINING_CAMP,
            NpcId.FRENITA,
            NpcId.ROMETTI,
            NpcId.HECKEL_FUNCH,
            NpcId.HUDO_GLENFAD,
            NpcId.GULLUCK,
            NpcId.FERNAHEI,
            NpcId.OBLI,
            NpcId.CHADWELL,
            NpcId.OGRE_MERCHANT,
            NpcId.OGRE_TRADER_GENSTORE,
            NpcId.SHANTAY_PASS_GUARD_MOVING,
            NpcId.SHANTAY_PASS_GUARD_STANDING,
            NpcId.ASSISTANT,
            NpcId.NURMOF,
            NpcId.SIEGFRIED_ERKLE,
            NpcId.TEA_SELLER,
            NpcId.FIONELLA,
            NpcId.LUNDAIL,
            NpcId.GARDENER);

    private static final Set<NpcId> COMMAND2 = Set.of(NpcId.GNOME_WAITER, NpcId.BLURBERRY_BARMAN);

    /**
     * Which command of the NPC is "Trade" on {@code world}: 1, 2, or 0 when it has no quick trade
     * there. Aubury's is his second where runecrafting is on, as his first then teleports.
     */
    public static int command(WorldProfile world, int npcId) {
        if (!world.rules().quickTrade()) {
            return 0;
        }
        NpcId npc = NpcId.getById(npcId);
        if (npc == NpcId.AUBURY) {
            return world.has(WorldProfile.Feature.RUNECRAFT) ? 2 : 1;
        }
        if (COMMAND2.contains(npc)) {
            return 2;
        }
        return COMMAND1.contains(npc) ? 1 : 0;
    }

    static Set<NpcId> command2() {
        return COMMAND2;
    }

    static Set<NpcId> command1() {
        return COMMAND1;
    }
}
