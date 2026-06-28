package openrsc.bot.core.defs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * NPC definitions, loaded from verbatim copies of the server's
 * {@code conf/server/defs/NpcDefs.json} + {@code NpcDefsCustom.json} (custom entries are appended
 * after base, mirroring {@code EntityHandler.loadNpcDefinitions}; the {@code NpcDefsPatch18}
 * overlay is NOT applied because Uranium runs {@code based_config_data: 85}). Refresh by re-copying
 * the two files from the server checkout.
 *
 * <p>The one consumer-critical bit is {@link #isAggressive(int)}: with
 * Uranium's {@code npc_blocking: 2}, the server's {@code PathValidation.isMobBlocking} refuses any
 * walk step onto a tile holding an NPC whose def has {@code attackable && aggressive} — the exact
 * mirror of {@code NPCDef.isAggressive()}. The walker's per-tick blocked-tile mask is built from
 * this flag.
 *
 * <p>File format is the server's {@code {"npcs": [ ... ]}} JSON with
 * lowercase field names ({@code id}, {@code aggressive}, {@code attackable}, {@code combatlvl},
 * {@code hits} as ints) — unlike the client-dump shape of the other data-dir defs.
 */
public final class NpcDefs {

  /**
   * One NPC def — the subset of the server's {@code NPCDef} scripts consume. {@code aggressive} is
   * the raw def flag; the server's {@code NPCDef.isAggressive()} is
   * {@code attackable && aggressive}.
   */
  public record Def(int id, String name, int combatLevel, int hits,
                    boolean attackable, boolean aggressive, int respawnTime,
                    String description, int attack, int strength, int defense,
                    boolean ranged, boolean members) {

    /**
     * Maximum melee damage this npc can deal in a single hit, derived from its {@code strength}
     * stat. Mirrors the server's classic-RSC combat formula
     * ({@code CombatFormula.getMeleeDamage} feeding {@code calculateMeleeDamage}). For an npc the
     * source-side terms all collapse — {@code styleBonus} and {@code weaponPowerPoints} are 0,
     * {@code prayerBonus} is 1.0, and the player-only {@code +8} constant is absent — leaving:
     * <pre>
     *   maxRoll = strength * 64
     *   hit     = (random(0, maxRoll - 1) + 320) / 640   // integer division
     * </pre>
     * The largest value {@code random(0, maxRoll - 1)} can produce is {@code maxRoll - 1}, so the
     * achievable max hit is {@code (strength * 64 - 1 + 320) / 640}. Returns 0 for non-combat npcs
     * ({@code strength == 0}). NPCs cannot range or cast magic in stock combat — even "mage" npcs
     * like the Dark Wizard attack purely with melee ({@code CombatEvent} only ever calls
     * {@code doMeleeDamage} for npc attacks; there is no magic stat in the def) — so this melee value
     * is the npc's effective max hit. The sole exceptions are a handful of boss plugins
     * (dragonfire breath, Kolodion) that apply damage directly outside the combat formula; those are
     * hardcoded per-plugin and not derivable from def data.
     */
    public int maxHit() {
      int maxRoll = strength * 64;
      return maxRoll <= 0 ? 0 : (maxRoll - 1 + 320) / 640;
    }
  }

  private final boolean[] aggressive;
  private final Def[] byId;

  private NpcDefs(boolean[] aggressive, Def[] byId) {
    this.aggressive = aggressive;
    this.byId = byId;
  }

  public int size() {
    return aggressive.length;
  }

  /**
   * Server {@code NPCDef.isAggressive()}: {@code attackable && aggressive}. Unknown ids return
   * {@code true} — over-blocking detours around a harmless NPC (slightly suboptimal);
   * under-blocking wedges the walker against the server's queue reset.
   */
  public boolean isAggressive(int id) {
    if (id < 0 || id >= aggressive.length) {
      return true;
    }
    return aggressive[id];
  }

  public String nameOf(int id) {
    Def d = get(id);
    return d != null ? d.name() : null;
  }

  /**
   * Full def for {@code id}, or {@code null} for an unknown id.
   */
  public Def get(int id) {
    return (id >= 0 && id < byId.length) ? byId[id] : null;
  }

  /**
   * Load base + optional custom defs. Either file may be absent — a data dir provisioned before
   * these defs existed must not break startup. With no base file every NPC id resolves through the
   * unknown-id fallback (= blocks), which over-detours but never wedges; a loud warning tells the
   * operator to re-copy the server defs.
   */
  public static NpcDefs load(Path baseFile, Path customFile) throws IOException {
    if (baseFile == null || !Files.exists(baseFile)) {
      LoggerFactory.getLogger(NpcDefs.class).warn(
          "NpcDefs.json missing at {} — treating EVERY npc as blocking "
          + "(over-detours); copy NpcDefs.json + NpcDefsCustom.json from "
          + "the server's conf/server/defs/ into data/ to fix", baseFile);
      return new NpcDefs(new boolean[0], new Def[0]);
    }
    ObjectMapper mapper = new ObjectMapper();
    List<Def> entries = new ArrayList<>();
    readInto(mapper, baseFile, entries);
    if (customFile != null && Files.exists(customFile)) {
      readInto(mapper, customFile, entries);
    }
    int maxId = 0;
    for (Def d : entries) {
      maxId = Math.max(maxId, d.id());
    }
    boolean[] agg = new boolean[maxId + 1];
    Def[] byId = new Def[maxId + 1];
    for (Def d : entries) {
      agg[d.id()] = d.attackable() && d.aggressive();
      byId[d.id()] = d;
    }
    return new NpcDefs(agg, byId);
  }

  private static void readInto(ObjectMapper mapper, Path file,
      List<Def> entries) throws IOException {
    JsonNode root = mapper.readTree(Files.readAllBytes(file));
    JsonNode npcs = root.path("npcs");
    if (!npcs.isArray()) {
      throw new IOException("no \"npcs\" array in " + file);
    }
    for (JsonNode n : npcs) {
      entries.add(new Def(
          n.path("id").asInt(-1),
          n.path("name").asString(null),
          n.path("combatlvl").asInt(0),
          n.path("hits").asInt(0),
          n.path("attackable").asInt(0) != 0,
          n.path("aggressive").asInt(0) != 0,
          n.path("respawnTime").asInt(0),
          n.path("description").asString(null),
          n.path("attack").asInt(0),
          n.path("strength").asInt(0),
          n.path("defense").asInt(0),
          n.path("ranged").asBoolean(false),
          n.path("isMembers").asInt(0) != 0));
    }
  }
}
