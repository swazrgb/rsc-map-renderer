package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code NpcCerters.xml} — certer NPC id → stall type + the certificates it exchanges. Mirrors the
 * server's {@code CerterDef}/{@code CertDef} ({@code EntityHandler.getCerterDef(id)}); cert order
 * matters — the dialog menu indexes into it.
 */
public record NpcCerters(Map<Integer, Def> byNpcId) {

  /**
   * Server {@code CertDef}: one item ↔ certificate pairing.
   */
  public record Cert(String name, int certId, int itemId, String fromCertOpt, String toCertOpt) {

  }

  /**
   * Server {@code CerterDef}: stall type ("ore", "fish", ...) + ordered certs.
   */
  public record Def(String type, List<Cert> certs) {

  }

  public static NpcCerters load(Path file) {
    Map<Integer, Def> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (npcId, d) -> {
      List<Cert> certs = new ArrayList<>();
      XmlTree certsEl = d.child("certs");
      if (certsEl != null) {
        for (XmlTree c : certsEl.children("CertDef")) {
          certs.add(new Cert(
              c.text("name"),
              c.intOf("certID", 0),
              c.intOf("itemID", 0),
              c.text("fromCertOpt"),
              c.text("toCertOpt")));
        }
      }
      m.put(npcId, new Def(d.text("type"), List.copyOf(certs)));
    });
    return new NpcCerters(Map.copyOf(m));
  }

  public Def get(int npcId) {
    return byNpcId.get(npcId);
  }

  public int size() {
    return byNpcId.size();
  }
}
