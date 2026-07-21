package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code ItemCraftingDef.xml} — crafting-index id → produced item/level/exp/gem. Mirrors the
 * server's {@code ItemCraftingDef} ({@code EntityHandler.getCraftingDef(id)}); {@code gemID} is −1
 * for plain (gemless) gold jewellery.
 */
public record CraftingDefs(Map<Integer, Def> byId) {

  public record Def(int requiredLvl, int itemId, int exp, int gemId) {

  }

  public static CraftingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> m.put(id, new Def(
        d.intOf("requiredLvl", 0),
        d.intOf("itemID", 0),
        d.intOf("exp", 0),
        d.intOf("gemID", -1))));
    return new CraftingDefs(Map.copyOf(m));
  }

  public Def get(int id) {
    return byId.get(id);
  }

  public int size() {
    return byId.size();
  }
}
