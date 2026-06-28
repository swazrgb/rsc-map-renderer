package openrsc.bot.core.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import openrsc.bot.api.id.ItemId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Item definitions, loaded from the server's authoritative {@code conf/server/defs/ItemDefs.json}
 * ({@code {"item":[...]}}) merged with {@code ItemDefsCustom.json} ({@code {"items":[...]}}, ids
 * 1290+) — mirroring {@code EntityHandler.loadItemDefinitions}. The {@code ItemDefsPatch18} overlay
 * is NOT applied because Uranium runs {@code based_config_data: 85}.
 *
 * <p>Wire-critical consumer: inventory packet decode (opcode 53) needs
 * {@link #isStackable(int)} per item id to decide whether to read a 4-byte amount field. All other
 * fields are exposed for scripts (prices, wear slots, level requirements, combat bonuses).
 */
public final class ItemDefs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(@JsonProperty("id") int id,
                      @JsonProperty("name") String name,
                      @JsonProperty("description") String description,
                      @JsonProperty("command") String command,
                      @JsonProperty("isStackable") int isStackable,
                      @JsonProperty("isUntradable") int isUntradable,
                      @JsonProperty("isWearable") int isWearable,
                      @JsonProperty("isMembersOnly") int isMembersOnly,
                      @JsonProperty("isNoteable") int isNoteable,
                      @JsonProperty("appearanceID") int appearanceID,
                      @JsonProperty("wearableID") int wearableID,
                      @JsonProperty("wearSlot") int wearSlot,
                      @JsonProperty("requiredLevel") int requiredLevel,
                      @JsonProperty("requiredSkillID") int requiredSkillID,
                      @JsonProperty("armourBonus") int armourBonus,
                      @JsonProperty("weaponAimBonus") int weaponAimBonus,
                      @JsonProperty("weaponPowerBonus") int weaponPowerBonus,
                      @JsonProperty("magicBonus") int magicBonus,
                      @JsonProperty("prayerBonus") int prayerBonus,
                      @JsonProperty("basePrice") int basePrice) {

  }

  private final Entry[] byId;
  /**
   * Server names that are shared by more than one item id (e.g. all 16 unidentified herbs are named
   * "Herb"). For these, {@link #nameOf(int)} disambiguates via the {@link ItemId} enum so the GUI
   * can tell which one it is. Names that are unique stay verbatim.
   */
  private final java.util.Set<String> ambiguousNames;

  private ItemDefs(Entry[] byId) {
    this.byId = byId;
    this.ambiguousNames = computeAmbiguousNames(byId);
  }

  private static java.util.Set<String> computeAmbiguousNames(Entry[] byId) {
    java.util.Map<String, Integer> counts = new java.util.HashMap<>();
    for (Entry e : byId) {
      if (e != null && e.name() != null) {
        counts.merge(e.name(), 1, Integer::sum);
      }
    }
    java.util.Set<String> dup = new java.util.HashSet<>();
    for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
      if (e.getValue() > 1) {
        dup.add(e.getKey());
      }
    }
    return dup;
  }

  /**
   * Load base + custom server defs. The base root key is {@code "item"}, the custom root key is
   * {@code "items"} (server quirk, mirrored from {@code EntityHandler}).
   */
  public static ItemDefs load(Path baseFile, Path customFile) throws IOException {
    ObjectMapper m = new ObjectMapper();
    List<Entry> all = new ArrayList<>(readArray(m, baseFile, "item"));
    if (customFile != null && Files.exists(customFile)) {
      all.addAll(readArray(m, customFile, "items"));
    }
    int max = 0;
    for (Entry e : all) {
      if (e.id() > max) {
        max = e.id();
      }
    }
    Entry[] arr = new Entry[max + 1];
    for (Entry e : all) {
      arr[e.id()] = e;
    }
    return new ItemDefs(arr);
  }

  private static List<Entry> readArray(ObjectMapper m, Path file, String rootKey)
      throws IOException {
    JsonNode root = m.readTree(Files.readAllBytes(file));
    JsonNode arr = root.path(rootKey);
    if (!arr.isArray()) {
      throw new IOException("no \"" + rootKey + "\" array in " + file);
    }
    List<Entry> out = new ArrayList<>(arr.size());
    for (JsonNode n : arr) {
      out.add(m.treeToValue(n, Entry.class));
    }
    return out;
  }

  /**
   * True if items of this id stack (one slot, 4-byte amount on the wire).
   */
  public boolean isStackable(int id) {
    Entry e = get(id);
    return e != null && e.isStackable() != 0;
  }

  /**
   * Display name. Used for logging and the GUI. Returns {@code "?"} for unknown ids.
   *
   * <p>When the server's name is shared by several ids (16 items are literally named "Herb"), the
   * raw name can't tell them apart. In that case we fall back to the {@link ItemId} enum, which
   * carries a distinct constant per id (e.g. {@code UNIDENTIFIED_GUAM_LEAF}), humanized to
   * {@code "Unidentified guam leaf"}. Unique names are returned verbatim.
   */
  public String nameOf(int id) {
    Entry e = get(id);
    if (e == null) {
      return "?";
    }
    String name = e.name();
    if (name != null && ambiguousNames.contains(name)) {
      ItemId enumId = ItemId.getById(id);
      if (enumId != ItemId.NOTHING && enumId.id() == id) {
        return humanize(enumId.name());
      }
    }
    return name;
  }

  /** {@code UNIDENTIFIED_GUAM_LEAF} &rarr; {@code "Unidentified guam leaf"}. */
  private static String humanize(String enumName) {
    String lower = enumName.replace('_', ' ').toLowerCase();
    return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }

  public Entry get(int id) {
    return (id >= 0 && id < byId.length) ? byId[id] : null;
  }

  public int size() {
    return byId.length;
  }
}
