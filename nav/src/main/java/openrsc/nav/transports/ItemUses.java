package openrsc.nav.transports;

import static openrsc.nav.transports.spec.ItemUse.consumeFromLandings;
import static openrsc.nav.transports.spec.ItemUse.use;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Items whose inventory command moves the player when used in the right place, one constant per
 * landing.
 */
public enum ItemUses implements TransportDef {
    /**
     * Works anywhere on the black hole, and spends the disk.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/DiskOfReturning.java#L17">DiskOfReturning.onOpInv</a>
     */
    DISK_OF_RETURNING(consumeFromLandings(
            ItemId.DISK_OF_RETURNING.id(), List.of(Rect.of(303, 3298, 307, 3302)), Point.of(311, 3348), Prereq.ALWAYS)),

    /**
     * The whistle on the square plot sends a player below stage 5 to the grey realm, and one at stage
     * 5 or done to the restored realm.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TheHolyGrail.java#L308">TheHolyGrail.onOpInv</a>
     */
    HOLY_GRAIL_WHISTLE_TO_REALM(use(
            ItemId.MAGIC_WHISTLE.id(),
            List.of(Rect.of(490, 652, 491, 653)),
            Point.of(396, 18),
            Prereq.notFact(Fact.questAtLeast(QuestId.THE_HOLY_GRAIL.id(), 5)))),
    HOLY_GRAIL_WHISTLE_TO_RESTORED_REALM(use(
            ItemId.MAGIC_WHISTLE.id(),
            List.of(Rect.of(490, 652, 491, 653)),
            Point.of(492, 18),
            Prereq.fact(Fact.questAtLeast(QuestId.THE_HOLY_GRAIL.id(), 5)))),
    /**
     * The server takes the whistle anywhere in either realm and the restored castle's upper rooms;
     * the entries are one tile at each place a player stands there, not the whole area.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TheHolyGrail.java#L314">TheHolyGrail.onOpInv</a>
     */
    HOLY_GRAIL_WHISTLE_HOME(use(
            ItemId.MAGIC_WHISTLE.id(),
            List.of(
                    Rect.of(396, 18, 396, 18),
                    Rect.of(410, 11, 410, 11),
                    Rect.of(408, 11, 408, 11),
                    Rect.of(414, 11, 414, 11),
                    Rect.of(392, 29, 392, 29),
                    Rect.of(421, 29, 421, 29),
                    Rect.of(420, 35, 420, 35),
                    Rect.of(492, 18, 492, 18),
                    Rect.of(514, 977, 514, 977),
                    Rect.of(514, 1923, 514, 1923)),
            Point.of(490, 651),
            Prereq.ALWAYS)),
    /**
     * The server takes the bell anywhere in the yard and hall; the entry is the yard tile beside where
     * the fisherman drops the bell.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TheHolyGrail.java#L338">TheHolyGrail.onOpInv</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TheHolyGrail.java#L149">TheHolyGrail.onTalkNpc</a>
     */
    HOLY_GRAIL_BELL_INTO_CASTLE(
            use(ItemId.BELL.id(), List.of(Rect.of(421, 29, 421, 29)), Point.of(420, 35), Prereq.ALWAYS)),
    ;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    ItemUses(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (ItemUses t : values()) {
            out.addAll(t.expand(env));
        }
        return out;
    }
}
