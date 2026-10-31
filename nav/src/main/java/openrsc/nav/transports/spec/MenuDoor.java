package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.Rows.allowOnly;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.MenuDoorTransport;
import openrsc.nav.transports.spec.Rows.Answers;
import openrsc.nav.transports.spec.Rows.CanKillAt;
import openrsc.nav.transports.spec.Rows.Note;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.Rows.Pays;
import openrsc.nav.transports.spec.Rows.Sequenced;
import openrsc.nav.transports.spec.Rows.When;

/**
 * Open a door or window from {@code from}, answer the dialogue it starts, and be moved to {@code to}:
 * one direction of it. {@code answers} is one keyword group per menu, matched by position when
 * {@code sequenced}. {@code fare} is what the crossing spends, or {@code null}.
 */
public record MenuDoor(
        int boundaryId,
        int x,
        int z,
        int dir,
        Point from,
        Point to,
        String[][] answers,
        boolean sequenced,
        Prereq gate,
        int cost,
        Pays fare,
        String note)
        implements TransportSpec {

    public static MenuDoor menuDoor(
            int boundaryId, int x, int z, int dir, Point from, Point to, int cost, Option... options) {
        allowOnly(
                "a menu door",
                options,
                Answers.class,
                Sequenced.class,
                When.class,
                Pays.class,
                CanKillAt.class,
                Note.class);
        return new MenuDoor(
                boundaryId,
                x,
                z,
                dir,
                from,
                to,
                Rows.answersOf(options),
                Rows.has(Sequenced.class, options),
                Rows.gateOf(options),
                cost,
                Rows.paysOf(options),
                Rows.noteOf(options));
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<MenuDoorTransport.MenuStep> steps = new ArrayList<>();
        for (String[] a : answers) {
            steps.add(new MenuDoorTransport.MenuStep(a));
        }
        MenuDoorTransport t = new MenuDoorTransport(
                        boundaryId, x, z, dir, from.x(), from.y(), to.x(), to.y(), steps, gate, cost, note)
                .withPlacement(id);
        if (sequenced) {
            t.sequenced();
        }
        if (fare != null) {
            t.consuming(fare.itemId(), fare.count());
        }
        return List.of(t);
    }
}
