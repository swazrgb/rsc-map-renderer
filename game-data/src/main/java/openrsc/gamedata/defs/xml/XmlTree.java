package openrsc.gamedata.defs.xml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An XML element: its name, its text and its child elements, in document order. The parser takes
 * what the server's definition files hold: declarations, comments, CDATA, self-closing elements, the
 * five named entities and numeric references. Attributes are skipped. It is its own parser rather
 * than {@code javax.xml}, which the TeaVM build of {@code nav-web} does not have.
 */
public final class XmlTree {

    public final String name;

    private final StringBuilder text = new StringBuilder();
    private final List<XmlTree> children = new ArrayList<>();

    private XmlTree(String name) {
        this.name = name;
    }

    /** An element built rather than parsed, whose {@link #text()} answers {@code text} trimmed. */
    public static XmlTree of(String name, String text, List<XmlTree> children) {
        XmlTree node = new XmlTree(name);
        node.text.append(text);
        node.children.addAll(children);
        return node;
    }

    /**
     * @throws java.io.UncheckedIOException when the file cannot be read
     * @throws RuntimeException when it is not a well-formed document
     */
    public static XmlTree parse(Path file) {
        try {
            return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read XML " + file, e);
        } catch (RuntimeException e) {
            throw new RuntimeException("failed to parse XML " + file, e);
        }
    }

    /** @throws IllegalStateException when the text is empty or not well formed */
    public static XmlTree parse(String xml) {
        XmlTree root = null;
        Deque<XmlTree> stack = new ArrayDeque<>();
        int n = xml.length();
        int i = 0;
        while (i < n) {
            int lt = xml.indexOf('<', i);
            int textEnd = lt < 0 ? n : lt;
            if (textEnd > i && !stack.isEmpty()) {
                appendDecoded(stack.peek().text, xml, i, textEnd);
            }
            if (lt < 0) {
                break;
            }
            if (xml.startsWith("<!--", lt)) {
                i = skipPast(xml, "-->", lt + 4);
            } else if (xml.startsWith("<![CDATA[", lt)) {
                int end = xml.indexOf("]]>", lt + 9);
                if (end < 0) {
                    throw new IllegalStateException("unterminated CDATA at " + lt);
                }
                if (!stack.isEmpty()) {
                    stack.peek().text.append(xml, lt + 9, end);
                }
                i = end + 3;
            } else if (xml.startsWith("<?", lt)) {
                i = skipPast(xml, "?>", lt + 2);
            } else if (xml.startsWith("<!", lt)) {
                i = skipPast(xml, ">", lt + 2);
            } else if (xml.startsWith("</", lt)) {
                int gt = xml.indexOf('>', lt);
                if (gt < 0 || stack.isEmpty()) {
                    throw new IllegalStateException("unbalanced end tag at " + lt);
                }
                String closing = xml.substring(lt + 2, gt).trim();
                XmlTree open = stack.pop();
                if (!open.name.equals(closing)) {
                    throw new IllegalStateException("</" + closing + "> closes <" + open.name + "> at " + lt);
                }
                i = gt + 1;
            } else {
                int gt = xml.indexOf('>', lt);
                if (gt < 0) {
                    throw new IllegalStateException("unterminated start tag at " + lt);
                }
                boolean selfClosing = xml.charAt(gt - 1) == '/';
                int nameEnd = lt + 1;
                while (nameEnd < gt && !Character.isWhitespace(xml.charAt(nameEnd)) && xml.charAt(nameEnd) != '/') {
                    nameEnd++;
                }
                XmlTree node = new XmlTree(xml.substring(lt + 1, nameEnd));
                if (stack.isEmpty()) {
                    if (root != null) {
                        throw new IllegalStateException("second root element <" + node.name + ">");
                    }
                    root = node;
                } else {
                    stack.peek().children.add(node);
                }
                if (!selfClosing) {
                    stack.push(node);
                }
                i = gt + 1;
            }
        }
        if (root == null) {
            throw new IllegalStateException("empty XML document");
        }
        if (!stack.isEmpty()) {
            throw new IllegalStateException("unclosed <" + stack.peek().name + ">");
        }
        return root;
    }

    private static int skipPast(String xml, String end, int from) {
        int at = xml.indexOf(end, from);
        if (at < 0) {
            throw new IllegalStateException("unterminated markup at " + from);
        }
        return at + end.length();
    }

    private static void appendDecoded(StringBuilder out, String xml, int from, int to) {
        int i = from;
        while (i < to) {
            int amp = i;
            // Bounded by this run of text: indexOf would scan the rest of the document for each run.
            while (amp < to && xml.charAt(amp) != '&') {
                amp++;
            }
            if (amp == to) {
                out.append(xml, i, to);
                return;
            }
            out.append(xml, i, amp);
            int semi = xml.indexOf(';', amp);
            if (semi < 0 || semi >= to) {
                throw new IllegalStateException("unterminated entity at " + amp);
            }
            String ent = xml.substring(amp + 1, semi);
            switch (ent) {
                case "amp" -> out.append('&');
                case "lt" -> out.append('<');
                case "gt" -> out.append('>');
                case "quot" -> out.append('"');
                case "apos" -> out.append('\'');
                default -> {
                    if (!ent.startsWith("#")) {
                        throw new IllegalStateException("unknown entity &" + ent + "; at " + amp);
                    }
                    int code = ent.startsWith("#x") || ent.startsWith("#X")
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    out.appendCodePoint(code);
                }
            }
            i = semi + 1;
        }
    }

    public List<XmlTree> children() {
        return children;
    }

    public List<XmlTree> children(String childName) {
        List<XmlTree> out = new ArrayList<>();
        for (XmlTree c : children) {
            if (c.name.equals(childName)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The first child named {@code childName}, or {@code null}. */
    public XmlTree child(String childName) {
        for (XmlTree c : children) {
            if (c.name.equals(childName)) {
                return c;
            }
        }
        return null;
    }

    /** This element's text, trimmed; empty when it has none. */
    public String text() {
        return text.toString().trim();
    }

    /** The trimmed text of the first child named {@code childName}, or {@code null} when there is none. */
    public String text(String childName) {
        XmlTree c = child(childName);
        return c == null ? null : c.text();
    }

    /**
     * The first child named {@code childName} as an integer, or {@code def} when it is missing or empty.
     *
     * @throws NumberFormatException when its text is not an integer
     */
    public int intOf(String childName, int def) {
        String t = text(childName);
        return (t == null || t.isEmpty()) ? def : Integer.parseInt(t);
    }
}
