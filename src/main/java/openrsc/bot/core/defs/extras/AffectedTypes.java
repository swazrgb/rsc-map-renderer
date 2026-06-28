package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.core.defs.xml.XmlTree;

/**
 * {@code ItemAffectedTypes.xml} — wieldable item type → list of equipment types it conflicts with
 * (what gets unequipped when you wield it). Mirrors {@code EntityHandler.itemAffectedTypes}
 * ({@code HashMap<Integer, int[]>}); {@code EntityHandler.getAffectedTypes(type)} returns an empty
 * array for unknown types.
 */
public record AffectedTypes(Map<Integer, List<Integer>> byType) {

  public static AffectedTypes load(Path file) {
    Map<Integer, List<Integer>> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (type, arr) -> {
      List<Integer> types = new ArrayList<>();
      for (XmlTree i : arr.children("int")) {
        types.add(Integer.parseInt(i.text()));
      }
      m.put(type, List.copyOf(types));
    });
    return new AffectedTypes(Map.copyOf(m));
  }

  /**
   * Server-parity lookup: empty list (never null) for unknown types.
   */
  public List<Integer> get(int type) {
    return byType.getOrDefault(type, List.of());
  }

  public int size() {
    return byType.size();
  }
}
