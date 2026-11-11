package openrsc.bot.api;

import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Targetable;
import openrsc.nav.walk.Yield;

public interface Npc extends Targetable, Attackable, Yield.Sighting {

    @Override
    int serverIndex();

    @Override
    default Targetable.Kind kind() {
        return Targetable.Kind.NPC;
    }

    @Override
    int id();

    @Override
    default NpcDef def() {
        return null;
    }

    default ServerData.NpcSpawn spawn() {
        return null;
    }

    @Override
    int x();

    @Override
    int z();

    int sprite();

    @Override
    default boolean inCombat() {
        return sprite() == 8 || sprite() == 9;
    }

    default long combatSinceTick() {
        return -1;
    }

    default int currentHits() {
        return 0;
    }

    default int maxHits() {
        return 0;
    }

    default int lastDamage() {
        return -1;
    }

    /**
     * Whether this view of the NPC was seen stepping onto a fully blocked tile, a step the server never makes: its
     * position is wrong until the server sends it afresh (it leaves view, fights, teleports) or the session ends.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/WalkingQueue.java#L93">WalkingQueue.processNextMovement</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L280">PathValidation.isBlocking</a>
     */
    default boolean desynced() {
        return false;
    }

    default long retreatTick() {
        return Long.MIN_VALUE;
    }

    default long lastDamageTick() {
        return -1;
    }

    default String lastChat() {
        return null;
    }

    default int lastChatTick() {
        return -1;
    }

    default int skullType() {
        return 0;
    }

    default int wield1() {
        return 0;
    }

    default int wield2() {
        return 0;
    }

    default int actionBubbleItemId() {
        return 0;
    }
}
