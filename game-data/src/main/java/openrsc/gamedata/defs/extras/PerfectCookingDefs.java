package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemPerfectCookingDef.xml} — cooked item id → level needed to never burn ("perfect cook").
 * Mirrors the server's {@code ItemPerfectCookingDef}
 * ({@code EntityHandler.getItemPerfectCookingDef(id)}); {@code exp} exists on the server class but
 * is absent from every XML entry (binds 0).
 */
public record PerfectCookingDefs(Map<Integer, Def> byItemId) {

  public record Def(int requiredLvl, int exp) {

  }

  public static PerfectCookingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("exp", 0))));
    return new PerfectCookingDefs(Map.copyOf(m));
  }

  public Def get(int itemId) {
    return byItemId.get(itemId);
  }

  public int size() {
    return byItemId.size();
  }
}
