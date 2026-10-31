package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code NpcCerters.xml}: the NPCs that swap items for certificates, by NPC id. The order of
 * a certer's {@link Def#certs()} is its menu order.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L213">EntityHandler.load</a>
 */
public record NpcCerters(Map<Integer, Def> byNpcId) {

    /** One certificate: {@code certId} swaps for {@code itemId}; the two strings are the menu options. */
    public record Cert(String name, int certId, int itemId, String fromCertOpt, String toCertOpt) {}

    /** A certer: {@code type} names what it certs, such as ore or fish. */
    public record Def(String type, List<Cert> certs) {}

    public static NpcCerters from(XmlTree root) {
        Map<Integer, Def> m = new HashMap<>();
        ExtrasXml.eachEntry(root, (npcId, d) -> {
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

    public int size() {
        return byNpcId.size();
    }
}
