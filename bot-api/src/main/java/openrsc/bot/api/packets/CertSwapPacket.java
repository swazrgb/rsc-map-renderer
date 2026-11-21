package openrsc.bot.api.packets;

import java.util.Map;
import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.gamedata.data.ItemId;
import org.jetbrains.annotations.NotNull;

/**
 * Turns the bank's certificate swap on or off for the rest of the session. While it is on, a deposit of a
 * certificate {@link #landsAs} names banks five of that item per certificate instead of the certificate.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceOptionHandler.java#L45-L47">InterfaceOptionHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L558-L564">Bank.depositItemFromInventory</a>
 */
@ExtendedOnly(Extension.CERT_DEPOSIT)
public record CertSwapPacket(boolean on) implements Packet {

    public static final int ITEMS_PER_CERTIFICATE = 5;

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L652-L710
    private static final Map<ItemId, ItemId> UNCERTED = Map.ofEntries(
            Map.entry(ItemId.IRON_ORE_CERTIFICATE, ItemId.IRON_ORE),
            Map.entry(ItemId.COAL_CERTIFICATE, ItemId.COAL),
            Map.entry(ItemId.MITHRIL_ORE_CERTIFICATE, ItemId.MITHRIL_ORE),
            Map.entry(ItemId.SILVER_CERTIFICATE, ItemId.SILVER),
            Map.entry(ItemId.GOLD_CERTIFICATE, ItemId.GOLD),
            Map.entry(ItemId.IRON_BAR_CERTIFICATE, ItemId.IRON_BAR),
            Map.entry(ItemId.STEEL_BAR_CERTIFICATE, ItemId.STEEL_BAR),
            Map.entry(ItemId.MITHRIL_BAR_CERTIFICATE, ItemId.MITHRIL_BAR),
            Map.entry(ItemId.SILVER_BAR_CERTIFICATE, ItemId.SILVER_BAR),
            Map.entry(ItemId.GOLD_BAR_CERTIFICATE, ItemId.GOLD_BAR),
            Map.entry(ItemId.LOBSTER_CERTIFICATE, ItemId.LOBSTER),
            Map.entry(ItemId.RAW_LOBSTER_CERTIFICATE, ItemId.RAW_LOBSTER),
            Map.entry(ItemId.SWORDFISH_CERTIFICATE, ItemId.SWORDFISH),
            Map.entry(ItemId.RAW_SWORDFISH_CERTIFICATE, ItemId.RAW_SWORDFISH),
            Map.entry(ItemId.BASS_CERTIFICATE, ItemId.BASS),
            Map.entry(ItemId.RAW_BASS_CERTIFICATE, ItemId.RAW_BASS),
            Map.entry(ItemId.SHARK_CERTIFICATE, ItemId.SHARK),
            Map.entry(ItemId.RAW_SHARK_CERTIFICATE, ItemId.RAW_SHARK),
            Map.entry(ItemId.YEW_LOGS_CERTIFICATE, ItemId.YEW_LOGS),
            Map.entry(ItemId.MAPLE_LOGS_CERTIFICATE, ItemId.MAPLE_LOGS),
            Map.entry(ItemId.WILLOW_LOGS_CERTIFICATE, ItemId.WILLOW_LOGS),
            Map.entry(ItemId.DRAGON_BONE_CERTIFICATE, ItemId.DRAGON_BONES),
            Map.entry(ItemId.LIMPWURT_ROOT_CERTIFICATE, ItemId.LIMPWURT_ROOT),
            Map.entry(ItemId.PRAYER_POTION_CERTIFICATE, ItemId.FULL_RESTORE_PRAYER_POTION),
            Map.entry(ItemId.SUPER_ATTACK_POTION_CERTIFICATE, ItemId.FULL_SUPER_ATTACK_POTION),
            Map.entry(ItemId.SUPER_DEFENSE_POTION_CERTIFICATE, ItemId.FULL_SUPER_DEFENSE_POTION),
            Map.entry(ItemId.SUPER_STRENGTH_POTION_CERTIFICATE, ItemId.FULL_SUPER_STRENGTH_POTION));

    /** The item a swapped deposit of {@code itemId} banks as: {@code itemId} itself when it is no such certificate. */
    public static int landsAs(int itemId) {
        ItemId cert = ItemId.getById(itemId);
        ItemId item = cert == null ? null : UNCERTED.get(cert);
        return item == null ? itemId : item.id();
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return InterfaceOption.SWAP_CERT.requires();
    }
}
