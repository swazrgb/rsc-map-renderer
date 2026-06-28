package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code FiremakingDef.xml} — log item id → level/exp/burn length. Mirrors the server's
 * {@code FiremakingDef} ({@code EntityHandler.getFiremakingDef(id)}). {@code length} is in seconds
 * as stored; the server's {@code FiremakingDef.getLength()} multiplies by 1000 for ms.
 */
public record FiremakingDefs(Map<Integer, Def> byLogId) {

  public record Def(int level, int exp, int length) {

  }

  public static FiremakingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("level", 0),
        d.intOf("exp", 0),
        d.intOf("length", 0))));
    return new FiremakingDefs(Map.copyOf(m));
  }

  public Def get(int logId) {
    return byLogId.get(logId);
  }

  public int size() {
    return byLogId.size();
  }
}
