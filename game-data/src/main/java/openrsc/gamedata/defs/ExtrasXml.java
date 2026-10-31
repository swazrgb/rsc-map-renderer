package openrsc.gamedata.defs;

import openrsc.gamedata.defs.xml.XmlTree;

final class ExtrasXml {

    interface EntryConsumer {

        void accept(int key, XmlTree value);
    }

    private ExtrasXml() {}

    /**
     * Calls {@code consumer} with each {@code <entry>} of a serialized map: its {@code <int>} key and
     * the element after it.
     *
     * @throws IllegalStateException when an entry has fewer than two children
     */
    static void eachEntry(XmlTree root, EntryConsumer consumer) {
        for (XmlTree entry : root.children("entry")) {
            var kids = entry.children();
            if (kids.size() < 2) {
                throw new IllegalStateException("malformed <entry> in <" + root.name + ">"
                        + " — expected <int>key</int> + value, got " + kids.size()
                        + " children");
            }
            consumer.accept(Integer.parseInt(kids.get(0).text()), kids.get(1));
        }
    }
}
