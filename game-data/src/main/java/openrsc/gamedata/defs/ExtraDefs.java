package openrsc.gamedata.defs;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * The skill tables of the server's {@code defs/extras}, with the spell list. Every {@code exp} in
 * them is in quarter points, the unit the server keeps experience in: 4 make one experience point.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L191">EntityHandler.load</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Skills.java#L39">Skills.originalCurveExperienceArray</a>
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
        RunecraftDefs runecraft,
        SpellDefs spells) {

    public static final List<String> EXTRAS_FILES = List.of(
            "ItemEdibleHeals.xml",
            "ObjectMining.xml",
            "ObjectFishing.xml",
            "ItemCookingDef.xml",
            "ItemPerfectCookingDef.xml",
            "ItemSmeltingDef.xml",
            "ItemSmithingDef.xml",
            "ObjectWoodcutting.xml",
            "FiremakingDef.xml",
            "ItemCraftingDef.xml",
            "ItemGemDef.xml",
            "ItemHerbDef.xml",
            "ItemHerbSecond.xml",
            "ItemLogCutDef.xml",
            "ItemArrowHeadDef.xml",
            "ItemBowStringDef.xml",
            "ItemDartTipDef.xml",
            "ItemUnIdentHerbDef.xml",
            "ItemAffectedTypes.xml",
            "NpcCerters.xml",
            "ObjectHarvesting.xml",
            "ObjectRunecraft.xml");

    public static final String SPELL_FILE = "SpellDef.xml";

    /**
     * Reads every table from {@code conf}'s tree. These are always the files of the current rules: the
     * server reads others on a world with {@code old_skill_defs} or {@code lacks_prayers}.
     *
     * @throws java.io.UncheckedIOException when a file is missing or unreadable
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L228">EntityHandler.getPath</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L183">EntityHandler.load</a>
     */
    public static ExtraDefs load(ServerConf conf) {
        Path x = conf.extras();
        return from(
                name -> XmlTree.parse(x.resolve(name)),
                XmlTree.parse(conf.defs().resolve(SPELL_FILE)));
    }

    /** The tables from parsed files: {@code extras} answers each of {@link #EXTRAS_FILES} by name. */
    public static ExtraDefs from(Function<String, XmlTree> extras, XmlTree spells) {
        return new ExtraDefs(
                EdibleHeals.from(extras.apply("ItemEdibleHeals.xml")),
                MiningDefs.from(extras.apply("ObjectMining.xml")),
                FishingDefs.from(extras.apply("ObjectFishing.xml")),
                CookingDefs.from(extras.apply("ItemCookingDef.xml")),
                PerfectCookingDefs.from(extras.apply("ItemPerfectCookingDef.xml")),
                SmeltingDefs.from(extras.apply("ItemSmeltingDef.xml")),
                SmithingDefs.from(extras.apply("ItemSmithingDef.xml")),
                WoodcuttingDefs.from(extras.apply("ObjectWoodcutting.xml")),
                FiremakingDefs.from(extras.apply("FiremakingDef.xml")),
                CraftingDefs.from(extras.apply("ItemCraftingDef.xml")),
                GemDefs.from(extras.apply("ItemGemDef.xml")),
                HerbDefs.from(extras.apply("ItemHerbDef.xml")),
                HerbSeconds.from(extras.apply("ItemHerbSecond.xml")),
                LogCutDefs.from(extras.apply("ItemLogCutDef.xml")),
                ArrowHeadDefs.from(extras.apply("ItemArrowHeadDef.xml")),
                BowStringDefs.from(extras.apply("ItemBowStringDef.xml")),
                DartTipDefs.from(extras.apply("ItemDartTipDef.xml")),
                UnIdentHerbDefs.from(extras.apply("ItemUnIdentHerbDef.xml")),
                AffectedTypes.from(extras.apply("ItemAffectedTypes.xml")),
                NpcCerters.from(extras.apply("NpcCerters.xml")),
                HarvestingDefs.from(extras.apply("ObjectHarvesting.xml")),
                RunecraftDefs.from(extras.apply("ObjectRunecraft.xml")),
                SpellDefs.from(spells));
    }

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
                + " runecraft=" + runecraft.size()
                + " spells=" + spells.size();
    }
}
