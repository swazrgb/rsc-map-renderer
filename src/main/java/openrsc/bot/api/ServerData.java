package openrsc.bot.api;

import java.util.List;
import java.util.Map;

/**
 * Typed read access to the server's skill-content tables (the {@code defs/extras/*.xml} files the
 * server's {@code EntityHandler} loads): what heals how much, which rock gives which ore at what
 * level, which raw cooks into what, etc. Scripts get it via {@link Bot#serverData()}.
 *
 * <p>Lookup semantics mirror the server exactly — same keys, same null/zero
 * behavior as the corresponding {@code EntityHandler} getter. The data is loaded once from the
 * server conf tree, so it can never drift from what the server actually does.
 */
public interface ServerData {

  /**
   * Item definition for {@code itemId}, or {@code null} for an unknown id (server
   * {@code ItemDefs.json} + custom overlay, the same binding the server's
   * {@code EntityHandler.getItemDef} serves). {@code basePrice} is the def base — actual shop
   * prices scale by stock.
   */
  Item item(int itemId);

  record Item(int id, String name, String description, int basePrice,
              boolean stackable, boolean wearable,
              int requiredLevel, int requiredSkillId, int wearSlot) {

    /**
     * Whether this item is a melee weapon — wield position 4 (the weapon slot) and not a
     * staff/bow/scythe. Mirrors the server's wieldPos-4 acceptance branch in {@code CutWeb}
     * ({@code wieldPosition()==4 && !name.contains("staff"|"bow"|"cythe")}); {@code wearSlot} is the
     * server's {@code wieldPosition}. The general "do I have something I can swing" capability —
     * used by web cutting ({@link Bot#carryingMeleeWeapon()}), and reusable wherever a melee weapon
     * is required. A knife is NOT a melee weapon (wearSlot −1); callers needing the web rule OR it
     * with a knife check.
     */
    public boolean isMeleeWeapon() {
      if (wearSlot != 4 || name == null) {
        return false;
      }
      String n = name.toLowerCase();
      return !n.contains("staff") && !n.contains("bow") && !n.contains("cythe");
    }
  }

  /**
   * NPC definition for {@code npcId}, or {@code null} for an unknown id (server
   * {@code NpcDefs.json} + custom overlay). {@code aggressive} is the raw def flag; the server's
   * {@code NPCDef.isAggressive()} is {@code attackable && aggressive}. {@code respawnTime} is in
   * <em>seconds</em> (the server schedules respawn at {@code respawnTime × NPC_RESPAWN_MULTIPLIER ×
   * 1000} ms, scaled); predict respawn in server ticks via {@code respawnTime × 1000 / 640}.
   */
  Npc npc(int npcId);

  /**
   * {@code maxHit} is the most melee damage this NPC can deal in one hit (from its strength stat,
   * see {@code NpcDefs.Def.maxHit}) — used to size the HP buffer to keep before fleeing to eat.
   */
  record Npc(int id, String name, int combatLevel, int hits,
             boolean attackable, boolean aggressive, int respawnTime, int maxHit) {

  }

  /**
   * Every NPC spawn the server populates, each with its roam rectangle, from {@code NpcLocs.json}
   * (see {@code openrsc.bot.core.defs.NpcLocs}). The server walks a roaming NPC to a random tile
   * inside {@code [min, max]}, so that rectangle is where the NPC can be — pair it with {@link
   * #npc}'s {@code aggressive} flag to know which tiles a low-level bot must keep clear of (aggro
   * fires at Chebyshev 1; in the wilderness the level check is waived so every aggressive NPC there
   * is a threat). Returns the full list; filter by id/area as needed. Empty if locs weren't loaded.
   */
  List<NpcSpawn> npcSpawns();

  /**
   * One NPC spawn: its predicted {@code serverIndex}, id, start tile, and {@code [min, max]} roam
   * rectangle (server {@code X/Y} = bot {@code x/z}).
   *
   * <p><b>serverIndex</b> is this spawn's 0-based position in the load order, which equals the
   * entity index the server assigns at boot: {@code WorldPopulator} registers every entry of
   * {@code NpcLocs.json} in file order into an {@code EntityList} that hands out indices
   * {@code 0,1,2,…} from an empty pool, and on a members world (this deployment) the F2P
   * {@code isP2P}/{@code isMembers} skip-filters are disabled so <em>every</em> entry spawns. So for
   * a freshly-booted server this matches the wire {@code serverIndex} of the live NPC — e.g. the Man
   * at {@code (118, 658)} is index {@code 1366}. Caveats: it is a <em>prediction</em> for the static
   * spawn set only — it is not re-derived after runtime churn (a permanently-unregistered baseline
   * NPC can have its index reused by a later dynamic spawn), and it says nothing about NPCs spawned
   * dynamically at runtime. Holds for the authentic {@code NpcLocs.json} with no custom loc files
   * appended.
   */
  record NpcSpawn(int serverIndex, int id, int startX, int startZ,
                  int minX, int minZ, int maxX, int maxZ) {

    /** True iff {@code (x, z)} lies within the roam rectangle expanded by {@code pad} on each side. */
    public boolean within(int x, int z, int pad) {
      return x >= minX - pad && x <= maxX + pad && z >= minZ - pad && z <= maxZ + pad;
    }
  }

  /**
   * The static spawn whose predicted {@link NpcSpawn#serverIndex} is {@code serverIndex}, or
   * {@code null} if out of range. Since {@code serverIndex} is the load-order position this is a
   * direct list index into {@link #npcSpawns()}.
   */
  default NpcSpawn npcSpawnByServerIndex(int serverIndex) {
    List<NpcSpawn> all = npcSpawns();
    return serverIndex >= 0 && serverIndex < all.size() ? all.get(serverIndex) : null;
  }

  /**
   * The first static spawn whose start tile is exactly {@code (x, z)}, or {@code null} if none. The
   * server does not dedupe same-tile spawns, so prefer {@link #npcSpawnByServerIndex} when you know
   * the index; this is a convenience for "what spawns where I'm standing?".
   */
  default NpcSpawn npcSpawnAt(int x, int z) {
    for (NpcSpawn s : npcSpawns()) {
      if (s.startX() == x && s.startZ() == z) {
        return s;
      }
    }
    return null;
  }

  /**
   * Every predicted serverIndex for spawns of NPC def {@code npcId}, in ascending order (one def
   * commonly has many spawns). Empty if the id has no static spawn.
   */
  default List<Integer> npcServerIndicesOf(int npcId) {
    List<Integer> out = new java.util.ArrayList<>();
    for (NpcSpawn s : npcSpawns()) {
      if (s.id() == npcId) {
        out.add(s.serverIndex());
      }
    }
    return out;
  }

  /**
   * Hits healed by eating {@code itemId}; 0 if not edible (server {@code getItemEdibleHeals}).
   */
  int healFor(int itemId);

  /**
   * Raw item id → heal amount, every edible the server knows.
   */
  Map<Integer, Integer> edibleHeals();

  /**
   * Mining table for a rock scenery id, or {@code null} if not minable (server
   * {@code getObjectMiningDef}). {@code respawnTime} is in <em>seconds</em> (server multiplies by
   * 1000 → {@code scaledGameMs} → ms; predict respawn in ticks via {@code respawnTime * 1000 / 640},
   * tick-rate-invariant). {@code depletion} is the percent chance to deplete per ore.
   */
  Mining mining(int sceneryId);

  record Mining(int requiredLevel, int oreId, int exp, int depletion, int respawnTime) {

  }

  /**
   * Cooking table for a raw item id, or {@code null} if not cookable (server
   * {@code getItemCookingDef}).
   */
  Cooking cooking(int rawItemId);

  record Cooking(int requiredLevel, int cookedId, int burnedId, int exp) {

  }

  /**
   * Smelting table for an ore item id, or {@code null} if not smeltable (server
   * {@code getItemSmeltingDef}). {@code reqOres} lists the
   * <em>additional</em> ore ids consumed alongside this one, with
   * multiplicity (e.g. iron ore → steel lists coal twice).
   */
  Smelting smelting(int oreId);

  record Smelting(int requiredLevel, int barId, int exp, List<Integer> reqOres) {

  }

  /**
   * Fishing table for a spot scenery id + <em>command index</em> (0 = command1, 1 = command2 — e.g.
   * spot 192 is "lure"/"bait": index 0 = the lure table, index 1 = the bait table), mirroring the
   * server's {@code getObjectFishingDef(id, click)}. {@code null} when the scenery isn't a fishing
   * spot or has no table at that index.
   *
   * <p>{@code fish} preserves the server's document order — highest
   * required level first; the server's catch roll walks it in that order.
   */
  Fishing fishing(int spotSceneryId, int commandIndex);

  record Fishing(int netId, int baitId, List<Fish> fish) {

  }

  record Fish(int fishId, int requiredLevel, int exp) {

  }

  /**
   * Woodcutting table for a tree scenery id, or {@code null} if not cuttable (server
   * {@code getObjectWoodcuttingDef}).
   */
  Woodcutting woodcutting(int sceneryId);

  record Woodcutting(int requiredLevel, int logId, int exp) {

  }

  /**
   * Firemaking table for a log item id, or {@code null} if not burnable (server
   * {@code getFiremakingDef}).
   */
  Firemaking firemaking(int logId);

  record Firemaking(int requiredLevel, int exp) {

  }

  /**
   * Every smithable product, in the server's array order — the anvil UI addresses entries
   * positionally ({@code getSmithingDef(index)}), and {@link #smithing(int)} scans by produced item
   * id.
   */
  List<Smithing> smithing();

  /**
   * Smithing entry producing {@code itemId}, or {@code null} (server {@code getSmithingDefbyID}).
   */
  Smithing smithing(int itemId);

  record Smithing(int requiredLevel, int bars, int itemId, int amount) {

  }

  /**
   * Runecrafting table for an altar scenery id, or {@code null} (server
   * {@code getObjectRunecraftDef}).
   */
  Runecraft runecraft(int altarSceneryId);

  record Runecraft(int requiredLevel, int runeId, String runeName, int exp) {

  }

  /**
   * Harvesting table for a scenery id (fruit trees, watermelons, ...), or {@code null} (server
   * {@code getObjectHarvestingDef}).
   */
  Harvesting harvesting(int sceneryId);

  record Harvesting(int requiredLevel, int produceId, int exp) {

  }

  /**
   * Perfect-cook table for a cooked item id, or {@code null} if it has no perfect-cook level
   * (server {@code getItemPerfectCookingDef}). {@code requiredLvl} is the level at which the food
   * never burns; {@code exp} exists on the server class but is unset in the XML (0).
   */
  PerfectCooking perfectCooking(int cookedItemId);

  record PerfectCooking(int requiredLevel, int exp) {

  }

  /**
   * Crafting table for a crafting-index id, or {@code null} (server {@code getCraftingDef}).
   * {@code gemId} is −1 for plain (gemless) gold jewellery.
   */
  Crafting crafting(int id);

  record Crafting(int requiredLevel, int itemId, int exp, int gemId) {

  }

  /**
   * Gem-cutting table for an uncut gem item id, or {@code null} (server {@code getItemGemDef}).
   */
  Gem gem(int uncutId);

  record Gem(int gemId, int requiredLevel, int exp) {

  }

  /**
   * Herblaw table for a clean herb item id → unfinished potion, or {@code null} (server
   * {@code getItemHerbDef}); {@code exp} exists on the server class but is unset in the XML (0).
   */
  Herb herb(int herbId);

  record Herb(int potionId, int requiredLevel, int exp) {

  }

  /**
   * Secondary-ingredient table for a (second item id, unfinished potion id) pair, or {@code null}
   * (server {@code getItemHerbSecond}). Server-parity linear scan — the table is a positional
   * array, not a map.
   */
  HerbSecond herbSecond(int secondId, int unfinishedId);

  record HerbSecond(int secondId, int unfinishedId, int potionId,
                    int requiredLevel, int exp) {

  }

  /**
   * Herb-identification table for an unidentified herb item id, or {@code null} (server
   * {@code getItemUnIdentHerbDef}).
   */
  UnIdentHerb unIdentHerb(int unidentId);

  record UnIdentHerb(int newId, int requiredLevel, int exp) {

  }

  /**
   * Fletching table for a log item id (arrow shafts, shortbow, longbow), or {@code null} (server
   * {@code getItemLogCutDef}). Shaft exp is derived on the server as {@code shaftAmount * 2}, not
   * stored.
   */
  LogCut logCut(int logId);

  record LogCut(int shaftAmount, int shaftLevel,
                int shortbowId, int shortbowLevel, int shortbowExp,
                int longbowId, int longbowLevel, int longbowExp) {

  }

  /**
   * Fletching table for an arrow-head item id → finished arrow, or {@code null} (server
   * {@code getItemArrowHeadDef}).
   */
  ArrowHead arrowHead(int id);

  record ArrowHead(int arrowId, int requiredLevel, int exp) {

  }

  /**
   * Fletching table for an unstrung bow item id → strung bow, or {@code null} (server
   * {@code getItemBowStringDef}).
   */
  BowString bowString(int id);

  record BowString(int bowId, int requiredLevel, int exp) {

  }

  /**
   * Fletching table for a dart-tip item id → finished dart, or {@code null} (server
   * {@code getItemDartTipDef}).
   */
  DartTip dartTip(int id);

  record DartTip(int dartId, int requiredLevel, int exp) {

  }

  /**
   * Equipment types unequipped when wielding an item of {@code wieldType}; empty list (never null)
   * for an unknown type, mirroring the server's {@code getAffectedTypes}.
   */
  List<Integer> affectedTypes(int wieldType);

  /**
   * Certificate-exchange stall for a certer NPC id, or {@code null} (server {@code getCerterDef}).
   * {@code certs} preserves document order — the exchange dialog menu indexes into it.
   */
  Certer certer(int npcId);

  record Certer(String type, List<Cert> certs) {

  }

  record Cert(String name, int certId, int itemId,
              String fromCertOpt, String toCertOpt) {

  }
}
