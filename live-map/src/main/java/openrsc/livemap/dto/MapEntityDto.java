package openrsc.livemap.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An NPC, player, scenery object, wall object or ground item a bot sees. {@code serverIndex} is its key within its
 * kind: a mob's server index, an object's tile and direction, a ground item's tile and rank by id in its pile. An
 * {@code id} of -1 marks landscape scenery or a wall that the bot's view shows gone. The fields past
 * {@code inCombat} are null when they do not apply, and are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapEntityDto(
        int serverIndex,
        int id,
        String name,
        int x,
        int z,
        boolean inCombat,
        Integer dir,
        String appearance,
        Integer hp,
        Integer maxHp,
        Integer dmg,
        Long dmgTick,
        String msg,
        Long msgTick,
        Integer bubble,
        Long bubbleTick,
        Integer combatLvl,
        Boolean skulled) {

    public MapEntityDto(int serverIndex, int id, String name, int x, int z, boolean inCombat) {
        this(
                serverIndex,
                id,
                name,
                x,
                z,
                inCombat,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static MapEntityDto npc(
            int serverIndex,
            int id,
            String name,
            int x,
            int z,
            boolean inCombat,
            int dir,
            Integer hp,
            Integer maxHp,
            Integer dmg,
            Long dmgTick,
            String msg,
            Long msgTick) {
        return new MapEntityDto(
                serverIndex,
                id,
                name,
                x,
                z,
                inCombat,
                dir,
                null,
                hp,
                maxHp,
                dmg,
                dmgTick,
                msg,
                msgTick,
                null,
                null,
                null,
                null);
    }

    public static MapEntityDto player(
            int serverIndex,
            String name,
            int x,
            int z,
            boolean inCombat,
            String appearance,
            int dir,
            Integer hp,
            Integer maxHp,
            Integer dmg,
            Long dmgTick,
            String msg,
            Long msgTick,
            Integer bubble,
            Long bubbleTick,
            Integer combatLvl,
            Boolean skulled) {
        return new MapEntityDto(
                serverIndex,
                0,
                name,
                x,
                z,
                inCombat,
                dir,
                appearance,
                hp,
                maxHp,
                dmg,
                dmgTick,
                msg,
                msgTick,
                bubble,
                bubbleTick,
                combatLvl,
                skulled);
    }

    public static MapEntityDto groundItem(int key, int id, String name, int x, int z) {
        return new MapEntityDto(
                key, id, name, x, z, false, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static MapEntityDto wall(int serverIndex, int id, String name, int x, int z, int dir) {
        return new MapEntityDto(
                serverIndex,
                id,
                name,
                x,
                z,
                false,
                dir,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
