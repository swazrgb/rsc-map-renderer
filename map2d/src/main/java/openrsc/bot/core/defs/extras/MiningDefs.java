package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ObjectMining.xml} — rock scenery id → ore/exp/level/respawn. Mirrors the server's
 * {@code ObjectMiningDef} (keyed exactly like {@code EntityHandler.getObjectMiningDef(id)}).
 */
public record MiningDefs(Map<Integer, Def> bySceneryId) {

  /**
   * {@code respawnTime} is in <em>seconds</em> (the server does {@code respawnTime * 1000} →
   * {@code scaledGameMs} → ms before scheduling the respawn; see {@code Mining.batchMining}). To
   * predict respawn in server ticks use {@code respawnTime * 1000 / 640} — the tick-rate scaling
   * cancels, so the tick count is independent of the server's actual tick rate. {@code depletion}
   * is the percent chance the rock depletes on a successful mine.
   */
  public record Def(int requiredLvl, int oreId, int exp, int depletion, int respawnTime) {

  }

  public static MiningDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("oreId", 0),
        d.intOf("exp", 0),
        d.intOf("depletion", 0),
        d.intOf("respawnTime", 0))));
    return new MiningDefs(Map.copyOf(m));
  }

  public Def get(int sceneryId) {
    return bySceneryId.get(sceneryId);
  }

  public int size() {
    return bySceneryId.size();
  }
}
