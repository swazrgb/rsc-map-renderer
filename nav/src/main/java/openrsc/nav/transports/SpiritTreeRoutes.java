package openrsc.nav.transports;

import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.ObjectTeleport.menuObjectFrom;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.SceneryId;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * The spirit trees, one constant per journey (a kind of tree and one of its destinations), each
 * derived from every placement of its tree. The stronghold tree takes the Grand Tree quest and the
 * others Tree Gnome Village. Every tree destroys a carried plague sample on the way, so none is
 * offered to a player carrying one.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/SpiritTrees.java#L29">SpiritTrees.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/SpiritTrees.java#L70">SpiritTrees.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/SpiritTrees.java#L110">SpiritTrees.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/SpiritTrees.java#L136">SpiritTrees.breakPlagueSample</a>
 */
public enum SpiritTreeRoutes implements TransportDef {
    STRONGHOLD_TO_KHAZARD(big(SceneryId.STRONGHOLD_SPIRIT_TREE, 0, 1, 629, 629, "khazard")),
    STRONGHOLD_TO_VARROCK(big(SceneryId.STRONGHOLD_SPIRIT_TREE, 0, 1, 161, 453, "varrock")),
    STRONGHOLD_TO_VILLAGE(big(SceneryId.STRONGHOLD_SPIRIT_TREE, 0, 1, 656, 694, "village")),

    VILLAGE_TO_KHAZARD(big(SceneryId.SPIRIT_TREE, -1, 0, 629, 629, "khazard")),
    VILLAGE_TO_VARROCK(big(SceneryId.SPIRIT_TREE, -1, 0, 161, 453, "varrock")),
    VILLAGE_TO_STRONGHOLD(big(SceneryId.SPIRIT_TREE, -1, 0, 703, 487, "stronghold")),

    YOUNG_TO_VILLAGE(young(1, 0)),
    ;

    private static final String WHERE = "where can i go";
    private static final int BIG_COST = 260;
    private static final int YOUNG_COST = 140;
    private static final int VILLAGE_DEST_X = 658;
    private static final int VILLAGE_DEST_Z = 695;

    // (dx, dz) is the entry tile's offset from the tree's own tile.
    private static TransportSpec big(SceneryId tree, int dx, int dz, int destX, int destZ, String keyword) {
        Prereq quest = tree == SceneryId.STRONGHOLD_SPIRIT_TREE
                ? Prereq.fact(Fact.questComplete(QuestId.GRAND_TREE.id()))
                : Prereq.fact(Fact.questComplete(QuestId.TREE_GNOME_VILLAGE.id()));
        Prereq gate = Prereq.all(quest, keepsTheSample());
        return everyScenery(
                tree.id(),
                p -> List.of(menuObjectFrom(
                        tree.id(),
                        p.x(),
                        p.z(),
                        p.x() + dx,
                        p.z() + dz,
                        destX,
                        destZ,
                        gate,
                        SpiritTreeRoutes.BIG_COST,
                        null,
                        new String[] {SpiritTreeRoutes.WHERE},
                        new String[] {keyword})));
    }

    // A method, not a field: the constants' initializers run before this enum's static fields are set.
    private static Prereq keepsTheSample() {
        return Prereq.fact(Fact.notCarrying(ItemId.PLAGUE_SAMPLE.id()));
    }

    private static TransportSpec young(int dx, int dz) {
        int id = SceneryId.YOUNG_SPIRIT_TREE.id();
        Prereq gate = Prereq.all(Prereq.fact(Fact.questComplete(QuestId.TREE_GNOME_VILLAGE.id())), keepsTheSample());
        return everyScenery(
                id,
                p -> List.of(menuObjectFrom(
                        id,
                        p.x(),
                        p.z(),
                        p.x() + dx,
                        p.z() + dz,
                        SpiritTreeRoutes.VILLAGE_DEST_X,
                        SpiritTreeRoutes.VILLAGE_DEST_Z,
                        gate,
                        SpiritTreeRoutes.YOUNG_COST,
                        null,
                        new String[] {"yes"})));
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    SpiritTreeRoutes(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (SpiritTreeRoutes r : values()) {
            out.addAll(r.expand(env));
        }
        return out;
    }
}
