package openrsc.nav.transports;

import static openrsc.nav.transports.spec.SpellTeleport.questSpell;
import static openrsc.nav.transports.spec.SpellTeleport.spell;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.Transport;
import openrsc.nav.transports.SpellTransport.RuneCost;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * The standard spellbook's teleports, one constant each: spell id, magic level, runes (both the gate
 * and the spend) and the tile the server lands the caster on.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1874">SpellHandler.handleTeleport</a>
 */
public enum TeleportSpellRoutes implements TransportDef {
    VARROCK_TELEPORT(spell(
            12,
            25,
            new RuneCost(ItemId.LAW_RUNE.id(), 1, ItemId.FIRE_RUNE.id(), 1, ItemId.AIR_RUNE.id(), 3),
            120,
            504)),
    LUMBRIDGE_TELEPORT(spell(
            15,
            31,
            new RuneCost(ItemId.LAW_RUNE.id(), 1, ItemId.EARTH_RUNE.id(), 1, ItemId.AIR_RUNE.id(), 3),
            120,
            648)),
    FALADOR_TELEPORT(spell(
            18,
            37,
            new RuneCost(ItemId.LAW_RUNE.id(), 1, ItemId.WATER_RUNE.id(), 1, ItemId.AIR_RUNE.id(), 3),
            312,
            552)),
    CAMELOT_TELEPORT(spell(22, 45, new RuneCost(ItemId.LAW_RUNE.id(), 1, ItemId.AIR_RUNE.id(), 5), 456, 456)),

    ARDOUGNE_TELEPORT(questSpell(
            26,
            51,
            new RuneCost(ItemId.LAW_RUNE.id(), 2, ItemId.WATER_RUNE.id(), 2),
            588,
            621,
            QuestId.PLAGUE_CITY.id(),
            "ardougne_scroll")),
    WATCHTOWER_TELEPORT(questSpell(
            31,
            58,
            new RuneCost(ItemId.LAW_RUNE.id(), 2, ItemId.EARTH_RUNE.id(), 2),
            493,
            3525,
            QuestId.WATCHTOWER.id(),
            "watchtower_scroll")),
    ;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    TeleportSpellRoutes(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (TeleportSpellRoutes s : values()) {
            out.addAll(s.expand(env));
        }
        return out;
    }
}
