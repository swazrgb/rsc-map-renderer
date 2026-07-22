package openrsc.gamedata.defs.xml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Minimal immutable XML tree over the JDK StAX reader, for the server's conf XMLs
 * ({@code DoorDef.xml}, {@code GameObjectDef.xml}, the {@code defs/extras/*.xml} skill tables).
 * These files are small (≤0.5&nbsp;MB) and their XStream-emitted shapes — positional
 * {@code <X-array>} lists and {@code <map><entry>} tables with nested defs — are easiest to consume
 * as a tree rather than via per-file event loops.
 *
 * <p>Only element names and text are kept; attributes don't occur in these
 * files.
 */
public final class XmlTree {

  public final String name;
  private final StringBuilder text = new StringBuilder();
  private final List<XmlTree> children = new ArrayList<>();

  private XmlTree(String name) {
    this.name = name;
  }

  public static XmlTree parse(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      XMLStreamReader r = XMLInputFactory.newDefaultFactory().createXMLStreamReader(in);
      XmlTree root = null;
      Deque<XmlTree> stack = new ArrayDeque<>();
      while (r.hasNext()) {
        switch (r.next()) {
          case XMLStreamConstants.START_ELEMENT -> {
            XmlTree node = new XmlTree(r.getLocalName());
            if (stack.isEmpty()) {
              root = node;
            } else {
              stack.peek().children.add(node);
            }
            stack.push(node);
          }
          case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
            if (!stack.isEmpty()) {
              stack.peek().text.append(r.getText());
            }
          }
          case XMLStreamConstants.END_ELEMENT -> stack.pop();
          default -> {
          }
        }
      }
      r.close();
      if (root == null) {
        throw new IllegalStateException("empty XML document");
      }
      return root;
    } catch (Exception e) {
      throw new RuntimeException("failed to parse XML " + file, e);
    }
  }

  public List<XmlTree> children() {
    return children;
  }

  /**
   * All direct children with the given element name, in document order.
   */
  public List<XmlTree> children(String childName) {
    List<XmlTree> out = new ArrayList<>();
    for (XmlTree c : children) {
      if (c.name.equals(childName)) {
        out.add(c);
      }
    }
    return out;
  }

  /**
   * First direct child with the given name, or {@code null}.
   */
  public XmlTree child(String childName) {
    for (XmlTree c : children) {
      if (c.name.equals(childName)) {
        return c;
      }
    }
    return null;
  }

  /**
   * Trimmed text content of this element.
   */
  public String text() {
    return text.toString().trim();
  }

  /**
   * Trimmed text of the named direct child, or {@code null} if absent.
   */
  public String text(String childName) {
    XmlTree c = child(childName);
    return c == null ? null : c.text();
  }

  /**
   * Integer value of the named direct child; {@code def} if absent/blank.
   */
  public int intOf(String childName, int def) {
    String t = text(childName);
    return (t == null || t.isEmpty()) ? def : Integer.parseInt(t);
  }
}
