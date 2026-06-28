package openrsc.bot.core.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.ServerData;
import openrsc.bot.core.defs.ItemDefs;
import openrsc.bot.core.defs.NpcDefs;
import openrsc.bot.core.defs.NpcLocs;
import openrsc.bot.core.defs.extras.ExtraDefs;
import openrsc.bot.core.defs.extras.FishingDefs;
import openrsc.bot.core.defs.extras.SmeltingDefs;

/**
 * {@link ServerData} adapter over the core-side def loaders. Pure projection — every lookup
 * delegates to the loader whose semantics already mirror the server's {@code EntityHandler}; the
 * API records carry the subset scripts consume (the full bindings stay on the loaders).
 */
public final class ServerDataImpl implements ServerData {

  private final ItemDefs itemDefs;
  private final NpcDefs npcDefs;
  private final ExtraDefs defs;
  private final List<Smithing> smithing;
  private final List<NpcSpawn> npcSpawns;
  /** id → projected def, built once at load so {@link #npc} is an O(1) array read with no per-call
   *  allocation (the def is immutable and shared). Null slot = no def for that id. */
  private final Npc[] npcById;

  /**
   * {@code itemDefs}/{@code npcDefs} may be null (tests exercising only the extras tables) —
   * {@link #item}/{@link #npc} then return null. NPC spawn locations default to empty.
   */
  public ServerDataImpl(ItemDefs itemDefs, NpcDefs npcDefs, ExtraDefs defs) {
    this(itemDefs, npcDefs, defs, List.of());
  }

  public ServerDataImpl(ItemDefs itemDefs, NpcDefs npcDefs, ExtraDefs defs,
      List<NpcLocs.Spawn> npcLocs) {
    this.itemDefs = itemDefs;
    this.npcDefs = npcDefs;
    this.defs = defs;
    List<Smithing> sm = new ArrayList<>(defs.smithing().size());
    for (var d : defs.smithing().defs()) {
      sm.add(new Smithing(d.level(), d.bars(), d.itemId(), d.amount()));
    }
    this.smithing = List.copyOf(sm);
    // serverIndex == load-order position: WorldPopulator registers npcLocs in this exact order into
    // an EntityList that hands out indices 0,1,2,… (members world => no F2P skip-filters), so on a
    // fresh boot the i-th spawn here is the i-th NPC the server registers. See NpcSpawn javadoc.
    List<NpcSpawn> ns = new ArrayList<>(npcLocs.size());
    for (int i = 0; i < npcLocs.size(); i++) {
      NpcLocs.Spawn s = npcLocs.get(i);
      ns.add(new NpcSpawn(i, s.id(), s.start().x(), s.start().z(),
          s.min().x(), s.min().z(), s.max().x(), s.max().z()));
    }
    this.npcSpawns = List.copyOf(ns);
    // Project every def once into the API record, indexed by id. npc(id) then never allocates —
    // it hands back the shared immutable instance (used per-tick by aggro checks, spawn def resolution).
    if (npcDefs == null) {
      this.npcById = new Npc[0];
    } else {
      Npc[] arr = new Npc[npcDefs.size()];
      for (int id = 0; id < arr.length; id++) {
        NpcDefs.Def d = npcDefs.get(id);
        if (d != null) {
          arr[id] = new Npc(d.id(), d.name(), d.combatLevel(), d.hits(),
              d.attackable(), d.aggressive(), d.respawnTime(), d.maxHit());
        }
      }
      this.npcById = arr;
    }
  }

  @Override
  public Item item(int itemId) {
    ItemDefs.Entry e = itemDefs == null ? null : itemDefs.get(itemId);
    return e == null ? null
        : new Item(e.id(), e.name(), e.description(), e.basePrice(),
            e.isStackable() != 0, e.isWearable() != 0,
            e.requiredLevel(), e.requiredSkillID(), e.wearSlot());
  }

  @Override
  public Npc npc(int npcId) {
    return (npcId >= 0 && npcId < npcById.length) ? npcById[npcId] : null;
  }

  @Override
  public List<NpcSpawn> npcSpawns() {
    return npcSpawns;
  }

  @Override
  public int healFor(int itemId) {
    return defs.edibleHeals().healFor(itemId);
  }

  @Override
  public Map<Integer, Integer> edibleHeals() {
    return defs.edibleHeals().byItemId();
  }

  @Override
  public Mining mining(int sceneryId) {
    var d = defs.mining().get(sceneryId);
    return d == null ? null
        : new Mining(d.requiredLvl(), d.oreId(), d.exp(), d.depletion(), d.respawnTime());
  }

  @Override
  public Cooking cooking(int rawItemId) {
    var d = defs.cooking().get(rawItemId);
    return d == null ? null
        : new Cooking(d.requiredLvl(), d.cookedId(), d.burnedId(), d.exp());
  }

  @Override
  public Smelting smelting(int oreId) {
    var d = defs.smelting().get(oreId);
    if (d == null) {
      return null;
    }
    List<Integer> reqOres = new ArrayList<>();
    for (SmeltingDefs.ReqOre r : d.reqOres()) {
      for (int i = 0; i < r.amount(); i++) {
        reqOres.add(r.oreId());
      }
    }
    return new Smelting(d.requiredLvl(), d.barId(), d.exp(), List.copyOf(reqOres));
  }

  @Override
  public Fishing fishing(int spotSceneryId, int commandIndex) {
    FishingDefs.Spot s = defs.fishing().get(spotSceneryId, commandIndex);
    if (s == null) {
      return null;
    }
    List<Fish> fish = new ArrayList<>(s.fish().size());
    for (FishingDefs.Fish f : s.fish()) {
      fish.add(new Fish(f.fishId(), f.requiredLevel(), f.exp()));
    }
    return new Fishing(s.netId(), s.baitId(), List.copyOf(fish));
  }

  @Override
  public Woodcutting woodcutting(int sceneryId) {
    var d = defs.woodcutting().get(sceneryId);
    return d == null ? null : new Woodcutting(d.requiredLevel(), d.logId(), d.exp());
  }

  @Override
  public Firemaking firemaking(int logId) {
    var d = defs.firemaking().get(logId);
    return d == null ? null : new Firemaking(d.level(), d.exp());
  }

  @Override
  public List<Smithing> smithing() {
    return smithing;
  }

  @Override
  public Smithing smithing(int itemId) {
    var d = defs.smithing().byItemId(itemId);
    return d == null ? null : new Smithing(d.level(), d.bars(), d.itemId(), d.amount());
  }

  @Override
  public Runecraft runecraft(int altarSceneryId) {
    var d = defs.runecraft().get(altarSceneryId);
    return d == null ? null : new Runecraft(d.requiredLvl(), d.runeId(), d.runeName(), d.exp());
  }

  @Override
  public Harvesting harvesting(int sceneryId) {
    var d = defs.harvesting().get(sceneryId);
    return d == null ? null : new Harvesting(d.requiredLvl(), d.prodId(), d.exp());
  }

  @Override
  public PerfectCooking perfectCooking(int cookedItemId) {
    var d = defs.perfectCooking().get(cookedItemId);
    return d == null ? null : new PerfectCooking(d.requiredLvl(), d.exp());
  }

  @Override
  public Crafting crafting(int id) {
    var d = defs.crafting().get(id);
    return d == null ? null : new Crafting(d.requiredLvl(), d.itemId(), d.exp(), d.gemId());
  }

  @Override
  public Gem gem(int uncutId) {
    var d = defs.gems().get(uncutId);
    return d == null ? null : new Gem(d.gemId(), d.requiredLvl(), d.exp());
  }

  @Override
  public Herb herb(int herbId) {
    var d = defs.herbs().get(herbId);
    return d == null ? null : new Herb(d.potionId(), d.requiredLvl(), d.exp());
  }

  @Override
  public HerbSecond herbSecond(int secondId, int unfinishedId) {
    var d = defs.herbSeconds().get(secondId, unfinishedId);
    return d == null ? null
        : new HerbSecond(d.secondId(), d.unfinishedId(), d.potionId(),
            d.requiredLvl(), d.exp());
  }

  @Override
  public UnIdentHerb unIdentHerb(int unidentId) {
    var d = defs.unidentHerbs().get(unidentId);
    return d == null ? null : new UnIdentHerb(d.newId(), d.requiredLvl(), d.exp());
  }

  @Override
  public LogCut logCut(int logId) {
    var d = defs.logCut().get(logId);
    return d == null ? null
        : new LogCut(d.shaftAmount(), d.shaftLvl(),
            d.shortbowId(), d.shortbowLvl(), d.shortbowExp(),
            d.longbowId(), d.longbowLvl(), d.longbowExp());
  }

  @Override
  public ArrowHead arrowHead(int id) {
    var d = defs.arrowHeads().get(id);
    return d == null ? null : new ArrowHead(d.arrowId(), d.requiredLvl(), d.exp());
  }

  @Override
  public BowString bowString(int id) {
    var d = defs.bowStrings().get(id);
    return d == null ? null : new BowString(d.bowId(), d.requiredLvl(), d.exp());
  }

  @Override
  public DartTip dartTip(int id) {
    var d = defs.dartTips().get(id);
    return d == null ? null : new DartTip(d.dartId(), d.requiredLvl(), d.exp());
  }

  @Override
  public List<Integer> affectedTypes(int wieldType) {
    return defs.affectedTypes().get(wieldType);
  }

  @Override
  public Certer certer(int npcId) {
    var d = defs.certers().get(npcId);
    if (d == null) {
      return null;
    }
    List<Cert> certs = new ArrayList<>(d.certs().size());
    for (var c : d.certs()) {
      certs.add(new Cert(c.name(), c.certId(), c.itemId(), c.fromCertOpt(), c.toCertOpt()));
    }
    return new Certer(d.type(), List.copyOf(certs));
  }
}
