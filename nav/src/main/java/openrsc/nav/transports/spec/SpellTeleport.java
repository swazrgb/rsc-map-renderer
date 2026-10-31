package openrsc.nav.transports.spec;

import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.SpellTransport;

/**
 * A teleport spell: cast from anywhere, it needs the Magic level and the runes, spends the runes and
 * lands on a fixed tile. {@code requiresQuestComplete} is the quest id a quest spell needs, or
 * {@code -1}, and {@code scrollKey} the cache key its reward scroll stores, or {@code null}.
 */
public record SpellTeleport(
        int spellId,
        int magicLevel,
        Map<Integer, Integer> runes,
        int destX,
        int destZ,
        int cost,
        int requiresQuestComplete,
        String scrollKey)
        implements TransportSpec {

    /** A cast's cost, in the planner's units of 10 per walked tile. */
    public static final int COST = 50;

    public static SpellTeleport spell(int spellId, int magicLevel, Map<Integer, Integer> runes, int destX, int destZ) {
        return new SpellTeleport(spellId, magicLevel, runes, destX, destZ, COST, -1, null);
    }

    /**
     * A spell the server refuses until the reward scroll of quest {@code questId} is read, which
     * stores cache key {@code scrollKey}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1839">SpellHandler.canTeleport</a>
     */
    public static SpellTeleport questSpell(
            int spellId,
            int magicLevel,
            Map<Integer, Integer> runes,
            int destX,
            int destZ,
            int questId,
            String scrollKey) {
        return new SpellTeleport(spellId, magicLevel, runes, destX, destZ, COST, questId, scrollKey);
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        return List.of(
                new SpellTransport(spellId, magicLevel, runes, destX, destZ, cost, requiresQuestComplete, scrollKey)
                        .withPlacement(id));
    }
}
