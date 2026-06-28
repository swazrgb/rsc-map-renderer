package openrsc.bot.core.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import openrsc.bot.core.defs.xml.XmlTree;

/**
 * {@code ItemHerbSecond.xml} — positional {@code <ItemHerbSecond-array>}, NOT a map. Mirrors
 * {@code EntityHandler.herbSeconds} ({@code ItemHerbSecond[]}): the server scans linearly for the
 * (secondID, unfinishedID) pair ({@code getItemHerbSecond(secondID, unfinishedID)}).
 */
public record HerbSeconds(List<Def> defs) {

  public record Def(int secondId, int unfinishedId, int potionId, int requiredLvl, int exp) {

  }

  public static HerbSeconds load(Path file) {
    List<Def> out = new ArrayList<>();
    for (XmlTree d : XmlTree.parse(file).children("ItemHerbSecond")) {
      out.add(new Def(
          d.intOf("secondID", 0),
          d.intOf("unfinishedID", 0),
          d.intOf("potionID", 0),
          d.intOf("requiredLvl", 0),
          d.intOf("exp", 0)));
    }
    return new HerbSeconds(List.copyOf(out));
  }

  /**
   * Server-parity linear scan ({@code EntityHandler.getItemHerbSecond}).
   */
  public Def get(int secondId, int unfinishedId) {
    for (Def d : defs) {
      if (d.secondId() == secondId && d.unfinishedId() == unfinishedId) {
        return d;
      }
    }
    return null;
  }

  public int size() {
    return defs.size();
  }
}
