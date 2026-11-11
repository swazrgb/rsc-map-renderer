package openrsc.livemap.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One bot as the map's stream sends it each tick: where it is, how it looks and what it sees. Null fields, and
 * the empty lists marked so, are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BotLiveDto(
        String username,
        String status,
        String scriptClass,
        String task,
        String description,
        Long serverTick,
        PositionDto position,
        Double fatiguePercent,
        Boolean sleeping,
        Boolean inCombat,
        Integer serverIndex,
        List<MapEntityDto> npcs,
        List<MapEntityDto> players,
        List<MapEntityDto> objects,
        List<MapEntityDto> wallObjects,
        Long sessionId,
        String appearance,
        Integer dir,
        Integer hits,
        Integer maxHits,
        Boolean skulled,
        Integer dmg,
        List<MapEntityDto> groundItems,
        String msg,
        Long msgTick,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ProjectileDto> projectiles,
        Integer bubble,
        Long bubbleTick,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> dialog,
        Integer combatLvl,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> groups,
        WindowsDto windows) {

    public BotLiveDto withAppearance(String remembered) {
        return remembered == null
                ? this
                : new BotLiveDto(
                        username,
                        status,
                        scriptClass,
                        task,
                        description,
                        serverTick,
                        position,
                        fatiguePercent,
                        sleeping,
                        inCombat,
                        serverIndex,
                        npcs,
                        players,
                        objects,
                        wallObjects,
                        sessionId,
                        remembered,
                        dir,
                        hits,
                        maxHits,
                        skulled,
                        dmg,
                        groundItems,
                        msg,
                        msgTick,
                        projectiles,
                        bubble,
                        bubbleTick,
                        dialog,
                        combatLvl,
                        groups,
                        windows);
    }

    public BotLiveDto withoutDescription() {
        return description == null
                ? this
                : new BotLiveDto(
                        username,
                        status,
                        scriptClass,
                        task,
                        null,
                        serverTick,
                        position,
                        fatiguePercent,
                        sleeping,
                        inCombat,
                        serverIndex,
                        npcs,
                        players,
                        objects,
                        wallObjects,
                        sessionId,
                        appearance,
                        dir,
                        hits,
                        maxHits,
                        skulled,
                        dmg,
                        groundItems,
                        msg,
                        msgTick,
                        projectiles,
                        bubble,
                        bubbleTick,
                        dialog,
                        combatLvl,
                        groups,
                        windows);
    }
}
