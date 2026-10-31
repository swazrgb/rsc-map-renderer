package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.TeleportRegion;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ItemCommandTransport;
import openrsc.nav.transports.SpellTransport;

/**
 * Rub a charged item anywhere in {@link TeleportRegion#ITEMS} and answer its menu with
 * {@code keywords}, landing on {@code to}. The gate is carrying the item, the jewellery opt-in, and
 * the refusals every teleport shares ({@link SpellTransport}).
 */
public record ItemRub(int itemId, String[] keywords, Point to, int cost) implements TransportSpec {

    /** The account's opt-in to item teleports, which every item teleport requires. */
    public static final EligibilityInputs.Fact JEWELLERY_ALLOWED = EligibilityInputs.Fact.teleportJewelleryAllowed();

    private static final List<EligibilityInputs.Fact> RUB_HERE = List.of(
            JEWELLERY_ALLOWED,
            SpellTransport.NOT_HOLDING_ANA,
            SpellTransport.NOT_CARRYING_PLAGUE_SAMPLE,
            SpellTransport.NOT_CARRYING_KARAMJA_RUM);

    public static ItemRub rub(int itemId, Point to, int cost, String... keywords) {
        return new ItemRub(itemId, keywords, to, cost);
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        Prereq[] facts = new Prereq[RUB_HERE.size() + 1];
        for (int i = 0; i < RUB_HERE.size(); i++) {
            facts[i] = Prereq.fact(RUB_HERE.get(i));
        }
        facts[RUB_HERE.size()] = Prereq.fact(EligibilityInputs.Fact.carriedAtLeast(itemId, 1));
        Prereq gate = Prereq.all(facts);
        return List.of(ItemCommandTransport.teleport(itemId, to, cost, gate, List.<String[]>of(keywords))
                .withPlacement(id));
    }
}
