package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemLogCutDef.xml} — log item id → fletching outputs (arrow shafts, shortbow, longbow).
 * Mirrors the server's {@code ItemLogCutDef} ({@code EntityHandler.getItemLogCutDef(id)}). Shaft
 * exp is derived on the server as {@code shaftAmount * 2} (0.5 exp per shaft), not stored.
 */
public record LogCutDefs(Map<Integer, Def> byLogId) {

  public record Def(int shaftAmount, int shaftLvl,
                    int shortbowId, int shortbowLvl, int shortbowExp,
                    int longbowId, int longbowLvl, int longbowExp) {

  }

  public static LogCutDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("shaftAmount", 0),
        d.intOf("shaftLvl", 0),
        d.intOf("shortbowID", 0),
        d.intOf("shortbowLvl", 0),
        d.intOf("shortbowExp", 0),
        d.intOf("longbowID", 0),
        d.intOf("longbowLvl", 0),
        d.intOf("longbowExp", 0))));
    return new LogCutDefs(Map.copyOf(m));
  }

  public Def get(int logId) {
    return byLogId.get(logId);
  }

  public int size() {
    return byLogId.size();
  }
}
