package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemHerbDef.xml} — clean herb item id → unfinished potion/level. Mirrors the server's
 * {@code ItemHerbDef} ({@code EntityHandler.getItemHerbDef(id)}); the {@code exp} field exists on
 * the server class but is absent from the XML entries (binds 0).
 */
public record HerbDefs(Map<Integer, Def> byHerbId) {

  public record Def(int potionId, int requiredLvl, int exp) {

  }

  public static HerbDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("potionId", 0),
        d.intOf("requiredLvl", 0),
        d.intOf("exp", 0))));
    return new HerbDefs(Map.copyOf(m));
  }

  public Def get(int herbId) {
    return byHerbId.get(herbId);
  }

  public int size() {
    return byHerbId.size();
  }
}
