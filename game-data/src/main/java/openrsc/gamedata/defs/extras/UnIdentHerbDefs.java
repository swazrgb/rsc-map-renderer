package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemUnIdentHerbDef.xml} — unidentified herb item id → identified herb/herblaw level/exp.
 * Mirrors the server's {@code ItemUnIdentHerbDef}
 * ({@code EntityHandler.getItemUnIdentHerbDef(id)}).
 */
public record UnIdentHerbDefs(Map<Integer, Def> byUnidentId) {

  public record Def(int requiredLvl, int newId, int exp) {

  }

  public static UnIdentHerbDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("newId", 0),
        d.intOf("exp", 0))));
    return new UnIdentHerbDefs(Map.copyOf(m));
  }

  public Def get(int unidentId) {
    return byUnidentId.get(unidentId);
  }

  public int size() {
    return byUnidentId.size();
  }
}
