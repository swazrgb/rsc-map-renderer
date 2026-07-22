package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemGemDef.xml} — uncut gem item id → cut gem/level/exp. Mirrors the server's
 * {@code ItemGemDef} ({@code EntityHandler.getItemGemDef(id)}).
 */
public record GemDefs(Map<Integer, Def> byUncutId) {

  public record Def(int gemId, int requiredLvl, int exp) {

  }

  public static GemDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("gemID", 0),
        d.intOf("requiredLvl", 0),
        d.intOf("exp", 0))));
    return new GemDefs(Map.copyOf(m));
  }

  public Def get(int uncutId) {
    return byUncutId.get(uncutId);
  }

  public int size() {
    return byUncutId.size();
  }
}
