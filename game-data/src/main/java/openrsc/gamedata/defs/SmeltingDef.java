package openrsc.gamedata.defs;

import java.util.List;

/**
 * {@code reqOres}: what the bar takes besides one of the ore it is keyed by; empty when nothing.
 */
public record SmeltingDef(int requiredLevel, int barId, int exp, List<ReqOre> reqOres) {}
