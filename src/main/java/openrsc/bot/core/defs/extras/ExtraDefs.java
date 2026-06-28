package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import openrsc.bot.core.runtime.ServerConf;

/**
 * Aggregate of every skill-content table under the server's {@code defs/extras/} — one typed loader
 * per XML, each mirroring the binding the server's {@code EntityHandler.load()} performs on the
 * same file.
 *
 * <p>Not included: {@code ObjectTelePoints.xml} (consumed by the transport
 * graph via {@link openrsc.bot.core.runtime.BotEnvironment#ladderTelepointsXml()}), the
 * {@code retro/} subtree, and {@code CombatOdyssey.json} (not XML, not loaded by EntityHandler).
 */
public record ExtraDefs(
    EdibleHeals edibleHeals,
    MiningDefs mining,
    FishingDefs fishing,
    CookingDefs cooking,
    PerfectCookingDefs perfectCooking,
    SmeltingDefs smelting,
    SmithingDefs smithing,
    WoodcuttingDefs woodcutting,
    FiremakingDefs firemaking,
    CraftingDefs crafting,
    GemDefs gems,
    HerbDefs herbs,
    HerbSeconds herbSeconds,
    LogCutDefs logCut,
    ArrowHeadDefs arrowHeads,
    BowStringDefs bowStrings,
    DartTipDefs dartTips,
    UnIdentHerbDefs unidentHerbs,
    AffectedTypes affectedTypes,
    NpcCerters certers,
    HarvestingDefs harvesting,
    RunecraftDefs runecraft
) {

  /**
   * Load every table from {@code <conf>/defs/extras}. A missing file is a hard error — the server
   * tree always carries all of them.
   */
  public static ExtraDefs load(ServerConf conf) {
    Path x = conf.extras();
    return new ExtraDefs(
        EdibleHeals.load(x.resolve("ItemEdibleHeals.xml")),
        MiningDefs.load(x.resolve("ObjectMining.xml")),
        FishingDefs.load(x.resolve("ObjectFishing.xml")),
        CookingDefs.load(x.resolve("ItemCookingDef.xml")),
        PerfectCookingDefs.load(x.resolve("ItemPerfectCookingDef.xml")),
        SmeltingDefs.load(x.resolve("ItemSmeltingDef.xml")),
        SmithingDefs.load(x.resolve("ItemSmithingDef.xml")),
        WoodcuttingDefs.load(x.resolve("ObjectWoodcutting.xml")),
        FiremakingDefs.load(x.resolve("FiremakingDef.xml")),
        CraftingDefs.load(x.resolve("ItemCraftingDef.xml")),
        GemDefs.load(x.resolve("ItemGemDef.xml")),
        HerbDefs.load(x.resolve("ItemHerbDef.xml")),
        HerbSeconds.load(x.resolve("ItemHerbSecond.xml")),
        LogCutDefs.load(x.resolve("ItemLogCutDef.xml")),
        ArrowHeadDefs.load(x.resolve("ItemArrowHeadDef.xml")),
        BowStringDefs.load(x.resolve("ItemBowStringDef.xml")),
        DartTipDefs.load(x.resolve("ItemDartTipDef.xml")),
        UnIdentHerbDefs.load(x.resolve("ItemUnIdentHerbDef.xml")),
        AffectedTypes.load(x.resolve("ItemAffectedTypes.xml")),
        NpcCerters.load(x.resolve("NpcCerters.xml")),
        HarvestingDefs.load(x.resolve("ObjectHarvesting.xml")),
        RunecraftDefs.load(x.resolve("ObjectRunecraft.xml")));
  }

  /**
   * One-line count summary for the load log.
   */
  public String summary() {
    return "edible=" + edibleHeals.size()
           + " mining=" + mining.size()
           + " fishing=" + fishing.size()
           + " cooking=" + cooking.size()
           + " perfectCooking=" + perfectCooking.size()
           + " smelting=" + smelting.size()
           + " smithing=" + smithing.size()
           + " woodcutting=" + woodcutting.size()
           + " firemaking=" + firemaking.size()
           + " crafting=" + crafting.size()
           + " gems=" + gems.size()
           + " herbs=" + herbs.size()
           + " herbSeconds=" + herbSeconds.size()
           + " logCut=" + logCut.size()
           + " arrowHeads=" + arrowHeads.size()
           + " bowStrings=" + bowStrings.size()
           + " dartTips=" + dartTips.size()
           + " unidentHerbs=" + unidentHerbs.size()
           + " affectedTypes=" + affectedTypes.size()
           + " certers=" + certers.size()
           + " harvesting=" + harvesting.size()
           + " runecraft=" + runecraft.size();
  }
}
