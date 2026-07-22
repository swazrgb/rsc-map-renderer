package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ItemSmithingDef.xml} — positional {@code <ItemSmithingDef-array>}, NOT a map. Mirrors
 * {@code EntityHandler.itemSmithing} ({@code ItemSmithingDef[]}): the server looks entries up
 * <em>by array index</em> ({@code getSmithingDef(id)} — the anvil UI sends a positional index) or
 * by a linear scan on the produced item id ({@code getSmithingDefbyID(itemID)}). Document order is
 * preserved.
 */
public record SmithingDefs(List<Def> defs) {

  /**
   * Server {@code ItemSmithingDef}: produced item, bars consumed, amount made.
   */
  public record Def(int level, int bars, int itemId, int amount) {

  }

  public static SmithingDefs load(Path file) {
    List<Def> out = new ArrayList<>();
    for (XmlTree d : XmlTree.parse(file).children("ItemSmithingDef")) {
      out.add(new Def(
          d.intOf("level", 0),
          d.intOf("bars", 0),
          d.intOf("itemID", 0),
          d.intOf("amount", 0)));
    }
    return new SmithingDefs(List.copyOf(out));
  }

  /**
   * Server-parity positional lookup ({@code EntityHandler.getSmithingDef}).
   */
  public Def byIndex(int index) {
    if (index < 0 || index >= defs.size()) {
      return null;
    }
    return defs.get(index);
  }

  /**
   * Server-parity scan on produced item id ({@code getSmithingDefbyID}).
   */
  public Def byItemId(int itemId) {
    for (Def d : defs) {
      if (d.itemId() == itemId) {
        return d;
      }
    }
    return null;
  }

  public int size() {
    return defs.size();
  }
}
