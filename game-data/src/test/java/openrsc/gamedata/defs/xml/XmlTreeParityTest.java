package openrsc.gamedata.defs.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.defs.ObjectTelePoints;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class XmlTreeParityTest {

    private record Ref(String name, String text, List<Ref> children) {}

    private static ServerConf conf() {
        try {
            return ServerConf.resolve();
        } catch (RuntimeException e) {
            Assumptions.abort("server conf tree not available");
            return null;
        }
    }

    @Test
    void everyConfXmlParsesAsStaxDid() throws Exception {
        ServerConf conf = conf();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(conf.defs())) {
            files = walk.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
        assertTrue(files.size() > 20, "the conf tree's XMLs: " + files.size());
        for (Path f : files) {
            assertSame(stax(f), XmlTree.parse(f), f.toString());
        }
    }

    @Test
    void teleportPointsReadAsTheStaxLoopReadThem() {
        Path f = conf().extras().resolve("ObjectTelePoints.xml");
        List<ObjectTelePoints.Point> parsed = ObjectTelePoints.load(f);
        assertTrue(parsed.size() > 10, "overrides: " + parsed.size());
        assertEquals(staxTelePoints(f), parsed);
    }

    private static void assertSame(Ref want, XmlTree got, String at) {
        assertEquals(want.name(), got.name, at);
        assertEquals(want.text(), got.text(), at + "/" + want.name());
        assertEquals(want.children().size(), got.children().size(), at + "/" + want.name() + " children");
        for (int i = 0; i < want.children().size(); i++) {
            assertSame(want.children().get(i), got.children().get(i), at + "/" + want.name() + "[" + i + "]");
        }
    }

    private static Ref stax(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = XMLInputFactory.newDefaultFactory().createXMLStreamReader(in);
            Deque<String> names = new ArrayDeque<>();
            Deque<StringBuilder> texts = new ArrayDeque<>();
            Deque<List<Ref>> kids = new ArrayDeque<>();
            kids.push(new ArrayList<>());
            while (r.hasNext()) {
                switch (r.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        names.push(r.getLocalName());
                        texts.push(new StringBuilder());
                        kids.push(new ArrayList<>());
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!texts.isEmpty()) {
                            texts.peek().append(r.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        Ref node = new Ref(names.pop(), texts.pop().toString().trim(), kids.pop());
                        kids.peek().add(node);
                    }
                    default -> {}
                }
            }
            return kids.pop().get(0);
        }
    }

    private static List<ObjectTelePoints.Point> staxTelePoints(Path file) {
        List<ObjectTelePoints.Point> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = XMLInputFactory.newDefaultFactory().createXMLStreamReader(in);
            int entryX = 0;
            int entryY = 0;
            int destX = 0;
            int destY = 0;
            String command = null;
            int section = 0;
            String element = "";
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("Point")) {
                        section = 1;
                    } else if (n.equals("TelePoint")) {
                        section = 2;
                    } else {
                        element = n;
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    String t = r.getText().trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (element.equals("x") && section == 1) {
                        entryX = Integer.parseInt(t);
                    } else if (element.equals("y") && section == 1) {
                        entryY = Integer.parseInt(t);
                    } else if (element.equals("x") && section == 2) {
                        destX = Integer.parseInt(t);
                    } else if (element.equals("y") && section == 2) {
                        destY = Integer.parseInt(t);
                    } else if (element.equals("command")) {
                        command = t;
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("entry") && command != null) {
                        out.add(new ObjectTelePoints.Point(entryX, entryY, command, destX, destY));
                        command = null;
                    }
                    if (n.equals("Point") || n.equals("TelePoint")) {
                        section = 0;
                    }
                    element = "";
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
