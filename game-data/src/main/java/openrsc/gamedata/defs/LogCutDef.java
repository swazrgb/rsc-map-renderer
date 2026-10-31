package openrsc.gamedata.defs;

/**
 * {@code shaftAmount}: arrow shafts cut from one log.
 */
public record LogCutDef(
        int shaftAmount,
        int shaftLevel,
        int shortbowId,
        int shortbowLevel,
        int shortbowExp,
        int longbowId,
        int longbowLevel,
        int longbowExp) {}
