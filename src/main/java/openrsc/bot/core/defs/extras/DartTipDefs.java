package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemDartTipDef.xml} — dart-tip item id → finished dart/level/exp. Mirrors the server's
 * {@code ItemDartTipDef} ({@code EntityHandler.getItemDartTipDef(id)}).
 */
public record DartTipDefs(Map<Integer, Def> byTipId) {

  public record Def(int dartId, int requiredLvl, int exp) {

  }

  public static DartTipDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("dartID", 0),
        d.intOf("requiredLvl", 0),
        d.intOf("exp", 0))));
    return new DartTipDefs(Map.copyOf(m));
  }

  public Def get(int tipId) {
    return byTipId.get(tipId);
  }

  public int size() {
    return byTipId.size();
  }
}
