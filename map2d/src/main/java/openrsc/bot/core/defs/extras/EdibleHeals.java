package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemEdibleHeals.xml} — item id → hits healed on eat. Mirrors
 * {@code EntityHandler.itemEdibleHeals} ({@code HashMap<Integer,Integer>});
 * {@code EntityHandler.getItemEdibleHeals(id)} returns 0 for non-edibles.
 */
public record EdibleHeals(Map<Integer, Integer> byItemId) {

  public static EdibleHeals load(Path file) {
    Map<Integer, Integer> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, v) -> m.put(id, Integer.parseInt(v.text())));
    return new EdibleHeals(Map.copyOf(m));
  }

  /**
   * Hits healed by eating {@code itemId}; 0 if not edible (server parity).
   */
  public int healFor(int itemId) {
    return byItemId.getOrDefault(itemId, 0);
  }

  public int size() {
    return byItemId.size();
  }
}
