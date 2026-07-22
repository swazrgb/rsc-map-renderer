package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ObjectHarvesting.xml} — harvestable scenery id → produce/level/exp. Mirrors the server's
 * {@code ObjectHarvestingDef} ({@code EntityHandler.getObjectHarvestingDef(id)}).
 */
public record HarvestingDefs(Map<Integer, Def> bySceneryId) {

  /**
   * {@code exhaust} = percent chance the object exhausts per harvest; {@code respawnTime} in server
   * ticks.
   */
  public record Def(int requiredLvl, int prodId, int exp, int exhaust, int respawnTime) {

  }

  public static HarvestingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("prodId", 0),
        d.intOf("exp", 0),
        d.intOf("exhaust", 0),
        d.intOf("respawnTime", 0))));
    return new HarvestingDefs(Map.copyOf(m));
  }

  public Def get(int sceneryId) {
    return bySceneryId.get(sceneryId);
  }

  public int size() {
    return bySceneryId.size();
  }
}
