package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemCookingDef.xml} — raw item id → cooked/burned/exp/level. Mirrors the server's
 * {@code ItemCookingDef} ({@code EntityHandler.getItemCookingDef(id)}).
 */
public record CookingDefs(Map<Integer, Def> byRawItemId) {

  public record Def(int requiredLvl, int cookedId, int burnedId, int exp) {

  }

  public static CookingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("cookedId", 0),
        d.intOf("burnedId", 0),
        d.intOf("exp", 0))));
    return new CookingDefs(Map.copyOf(m));
  }

  public Def get(int rawItemId) {
    return byRawItemId.get(rawItemId);
  }

  public int size() {
    return byRawItemId.size();
  }
}
