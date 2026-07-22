package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemBowStringDef.xml} — unstrung bow item id → strung bow/level/exp. Mirrors the server's
 * {@code ItemBowStringDef} ({@code EntityHandler.getItemBowStringDef(id)}).
 */
public record BowStringDefs(Map<Integer, Def> byUnstrungId) {

  public record Def(int bowId, int requiredLvl, int exp) {

  }

  public static BowStringDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("bowID", 0),
        d.intOf("requiredLvl", 0),
        d.intOf("exp", 0))));
    return new BowStringDefs(Map.copyOf(m));
  }

  public Def get(int unstrungId) {
    return byUnstrungId.get(unstrungId);
  }

  public int size() {
    return byUnstrungId.size();
  }
}
