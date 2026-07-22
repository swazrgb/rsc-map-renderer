package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ObjectRunecraft.xml} — altar scenery id → rune/level/exp. Mirrors the server's
 * {@code ObjectRunecraftDef} ({@code EntityHandler.getObjectRunecraftDef(id)}).
 */
public record RunecraftDefs(Map<Integer, Def> byAltarId) {

  public record Def(int requiredLvl, int runeId, String runeName, int exp) {

  }

  public static RunecraftDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("runeId", 0),
        d.text("runeName"),
        d.intOf("exp", 0))));
    return new RunecraftDefs(Map.copyOf(m));
  }

  public Def get(int altarSceneryId) {
    return byAltarId.get(altarSceneryId);
  }

  public int size() {
    return byAltarId.size();
  }
}
