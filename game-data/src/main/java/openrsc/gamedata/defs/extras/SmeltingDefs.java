package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemSmeltingDef.xml} — ore item id → bar/exp/level + additional ores required. Mirrors the
 * server's {@code ItemSmeltingDef} with its {@code ReqOreDef[]}
 * ({@code EntityHandler.getItemSmeltingDef(id)}). {@code <reqOres />} (the empty XStream form)
 * binds to an empty list.
 */
public record SmeltingDefs(Map<Integer, Def> byOreId) {

  /**
   * Server {@code ReqOreDef}: an additional ore + how many of it.
   */
  public record ReqOre(int oreId, int amount) {

  }

  public record Def(int requiredLvl, int barId, int exp, List<ReqOre> reqOres) {

  }

  public static SmeltingDefs load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, d) -> {
      List<ReqOre> reqOres = new ArrayList<>();
      XmlTree reqs = d.child("reqOres");
      if (reqs != null) {
        for (XmlTree r : reqs.children("ReqOreDef")) {
          reqOres.add(new ReqOre(r.intOf("oreId", 0), r.intOf("amount", 0)));
        }
      }
      m.put(id, new Def(
          d.intOf("requiredLvl", 0),
          d.intOf("barId", 0),
          d.intOf("exp", 0),
          List.copyOf(reqOres)));
    });
    return new SmeltingDefs(Map.copyOf(m));
  }

  public Def get(int oreId) {
    return byOreId.get(oreId);
  }

  public int size() {
    return byOreId.size();
  }
}
