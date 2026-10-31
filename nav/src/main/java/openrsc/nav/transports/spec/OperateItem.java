package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ItemCommandTransport;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.When;

/**
 * An item's own command that lands on a fixed tile, with no menu, such as a skillcape's teleport. The
 * item is kept. Gated on carrying it and on {@link ItemRub#JEWELLERY_ALLOWED}.
 */
public record OperateItem(int itemId, Point to, int cost, Prereq gate, String note) implements TransportSpec {

    public static OperateItem operate(int itemId, Point to, int cost, Option... options) {
        allowOnly("an item command", options, When.class, CanKillAt.class, Note.class);
        return new OperateItem(itemId, to, cost, Rows.gateOf(options), Rows.noteOf(options));
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        Prereq allowed = Prereq.fact(ItemRub.JEWELLERY_ALLOWED);
        Prereq carried = Prereq.fact(Fact.carriedAtLeast(itemId, 1));
        Prereq all = gate == Prereq.ALWAYS ? Prereq.all(allowed, carried) : Prereq.all(allowed, gate, carried);
        return List.of(ItemCommandTransport.teleport(itemId, to, cost, all, List.of())
                .withNote(note)
                .withPlacement(id));
    }
}
