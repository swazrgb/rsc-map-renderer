package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code defs/SpellDef.xml} — positional {@code <SpellDef-array>}, NOT a map: the wire spell id IS
 * the array index. Mirrors {@code EntityHandler.spells} ({@code SpellDef[]},
 * {@code getSpellDef(id)}) on the non-retro branch the Uranium deployment runs
 * ({@code LACKS_PRAYERS=false} ⇒ {@code SpellDef.xml}, not {@code SpellDefRetro.xml}). Document
 * order is preserved.
 *
 * <p>Loaded mainly so ids in logs/describe lines can be rendered as names ("Wind strike" instead
 * of "spell 0"); {@code reqLevel} rides along since it's the other field scripts ask about.
 */
public record SpellDefs(List<Def> defs) {

  /** Server {@code SpellDef} projection: the fields the bot consumes. */
  public record Def(int id, String name, int requiredLevel) {

  }

  public static SpellDefs load(Path file) {
    List<Def> out = new ArrayList<>();
    int id = 0;
    for (XmlTree d : XmlTree.parse(file).children("SpellDef")) {
      out.add(new Def(id++, d.text("name"), d.intOf("reqLevel", 0)));
    }
    return new SpellDefs(List.copyOf(out));
  }

  /** Server-parity positional lookup ({@code EntityHandler.getSpellDef}); null out of range. */
  public Def get(int spellId) {
    if (spellId < 0 || spellId >= defs.size()) {
      return null;
    }
    return defs.get(spellId);
  }

  public int size() {
    return defs.size();
  }
}
