package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ObjectWoodcutting.xml} — tree scenery id → log/exp/level/respawn + per-axe success-rate
 * bounds. Mirrors the server's {@code ObjectWoodcuttingDef}
 * ({@code EntityHandler.getObjectWoodcuttingDef(id)}).
 */
public record WoodcuttingDefs(Map<Integer, Def> bySceneryId) {

  /**
   * Axe tiers in the server's order — indexes into {@link Def#low()} / {@link Def#high()}.
   */
  public static final String[] AXE_TIERS = {
      "bronze", "iron", "steel", "black", "mithril", "adamantite", "rune", "dragon"};

  /**
   * {@code fell} = percent chance the tree falls on a successful cut; {@code respawnTime} in server
   * ticks. {@code low}/{@code high} are the per-axe success-rate bounds (numerators out of 1024 at
   * level 1 / level 99), indexed per {@link #AXE_TIERS}.
   */
  public record Def(int requiredLevel, int logId, int exp, int fell, int respawnTime,
                    int[] low, int[] high) {

  }

  public static WoodcuttingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> {
      int[] low = new int[AXE_TIERS.length];
      int[] high = new int[AXE_TIERS.length];
      for (int i = 0; i < AXE_TIERS.length; i++) {
        String tier = Character.toUpperCase(AXE_TIERS[i].charAt(0)) + AXE_TIERS[i].substring(1);
        low[i] = d.intOf("low" + tier, 0);
        high[i] = d.intOf("high" + tier, 0);
      }
      m.put(id, new Def(
          d.intOf("requiredLevel", 0),
          d.intOf("logId", 0),
          d.intOf("exp", 0),
          d.intOf("fell", 0),
          d.intOf("respawnTime", 0),
          low, high));
    });
    return new WoodcuttingDefs(Map.copyOf(m));
  }

  public Def get(int sceneryId) {
    return bySceneryId.get(sceneryId);
  }

  public int size() {
    return bySceneryId.size();
  }
}
