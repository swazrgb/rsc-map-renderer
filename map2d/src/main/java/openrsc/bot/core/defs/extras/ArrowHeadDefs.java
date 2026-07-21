package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemArrowHeadDef.xml} — arrow-head item id → finished arrow/level/exp. Mirrors the
 * server's {@code ItemArrowHeadDef} ({@code EntityHandler.getItemArrowHeadDef(id)}).
 */
public record ArrowHeadDefs(Map<Integer, Def> byHeadId) {

  public record Def(int arrowId, int requiredLvl, int exp) {

  }

  public static ArrowHeadDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("arrowID", 0),
        d.intOf("requiredLvl", 0),
        d.intOf("exp", 0))));
    return new ArrowHeadDefs(Map.copyOf(m));
  }

  public Def get(int headId) {
    return byHeadId.get(headId);
  }

  public int size() {
    return byHeadId.size();
  }
}
