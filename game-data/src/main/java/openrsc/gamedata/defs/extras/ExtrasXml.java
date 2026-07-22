package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * Shared plumbing for the {@code defs/extras/*.xml} loaders. The server serializes these with
 * XStream as either {@code <map><entry><int>key</int>
 * <SomeDef>...</SomeDef></entry></map>} tables or positional
 * {@code <X-array>} lists; each loader in this package mirrors the binding the server's
 * {@code EntityHandler.load()} performs on the same file.
 */
final class ExtrasXml {

  private ExtrasXml() {
  }

  interface EntryConsumer {

    void accept(int key, XmlTree value);
  }

  /**
   * Iterate a {@code <map>} document: for each {@code <entry>}, the first child element is the
   * {@code <int>} key, the second the value def.
   */
  static void eachEntry(Path file, EntryConsumer consumer) {
    XmlTree root = XmlTree.parse(file);
    for (XmlTree entry : root.children("entry")) {
      var kids = entry.children();
      if (kids.size() < 2) {
        throw new IllegalStateException("malformed <entry> in " + file
                                        + " — expected <int>key</int> + value, got " + kids.size()
                                        + " children");
      }
      consumer.accept(Integer.parseInt(kids.get(0).text()), kids.get(1));
    }
  }
}
