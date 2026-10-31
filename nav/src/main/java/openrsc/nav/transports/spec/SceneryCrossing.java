package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ObjectInteractTransport;
import openrsc.nav.transports.ObjectMenuTransport;
import openrsc.nav.transports.spec.Rows.Answers;
import openrsc.nav.transports.spec.Rows.AwaitsDialog;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.ClickAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.Pays;
import openrsc.nav.transports.spec.Rows.SecondCommand;
import openrsc.nav.transports.spec.Rows.UsesItem;
import openrsc.nav.transports.spec.Rows.When;

/**
 * Operate one object from a stated tile and be moved to a fixed tile: one arm of a gate, cart or
 * ship whose handler lands the player by the side they stand on. The object is taken to stand on
 * {@code from} unless a {@code clickAt} option places it; {@code object} is {@code null} then.
 */
public record SceneryCrossing(
        int objectId,
        Point from,
        Point to,
        Point object,
        Prereq gate,
        int cost,
        boolean menu,
        String[][] answers,
        boolean secondCommand,
        boolean awaitsDialog,
        Pays fare,
        int useItemId,
        String note)
        implements TransportSpec {

    /**
     * Click the object, or use an item on it, and be moved. A used item that is spent is debited like a
     * fare, and carrying one use of it is part of the gate.
     */
    public static SceneryCrossing scenery(int objectId, Point from, Point to, int cost, Option... options) {
        allowOnly(
                "a scenery click",
                options,
                ClickAt.class,
                When.class,
                Pays.class,
                AwaitsDialog.class,
                UsesItem.class,
                CanKillAt.class,
                Note.class);
        UsesItem item = Rows.find(UsesItem.class, options);
        Prereq gate = Rows.gateOf(options);
        Pays spend = Rows.paysOf(options);
        if (item != null) {
            Prereq carried = Rows.carrying(item.itemId(), Math.max(1, item.spend()));
            gate = gate == Prereq.ALWAYS ? carried : Prereq.all(carried, gate);
            spend = item.spend() > 0 ? new Pays(item.itemId(), item.spend()) : null;
        }
        return new SceneryCrossing(
                objectId,
                from,
                to,
                Rows.clickAtOf(options),
                gate,
                cost,
                false,
                null,
                false,
                Rows.has(AwaitsDialog.class, options),
                spend,
                item == null ? -1 : item.itemId(),
                Rows.noteOf(options));
    }

    /** Click the object, with its first or second command, and answer the menus it opens. */
    public static SceneryCrossing sceneryMenu(int objectId, Point from, Point to, int cost, Option... options) {
        allowOnly(
                "a scenery menu",
                options,
                ClickAt.class,
                When.class,
                Pays.class,
                Answers.class,
                SecondCommand.class,
                CanKillAt.class,
                Note.class);
        return new SceneryCrossing(
                objectId,
                from,
                to,
                Rows.clickAtOf(options),
                Rows.gateOf(options),
                cost,
                true,
                Rows.answersOf(options),
                Rows.has(SecondCommand.class, options),
                false,
                Rows.paysOf(options),
                -1,
                Rows.noteOf(options));
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        if (menu) {
            Point at = object != null ? object : from;
            ObjectMenuTransport t = new ObjectMenuTransport(
                            objectId, at.x(), at.y(), from.x(), from.y(), to.x(), to.y(), answers, gate, cost, note)
                    .withPlacement(id);
            if (secondCommand) {
                t.useCommand2();
            }
            if (fare != null) {
                t.consuming(fare.itemId(), fare.count());
            }
            return List.of(t);
        }
        ObjectInteractTransport t = new ObjectInteractTransport(
                        objectId, from.x(), from.y(), to.x(), to.y(), gate, cost)
                .withNote(note)
                .withPlacement(id);
        if (object != null) {
            t.atScenery(object.x(), object.y());
        }
        if (awaitsDialog) {
            t.awaitsDialog();
        }
        if (useItemId >= 0) {
            t.usingItem(useItemId);
        }
        if (fare != null) {
            t.consuming(fare.itemId(), fare.count());
        }
        return List.of(t);
    }
}
