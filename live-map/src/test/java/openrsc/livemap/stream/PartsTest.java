package openrsc.livemap.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartsTest {

    record Line(int id, String what) {}

    record Inner(String bot, List<Line> lines) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Outer(String name, String gone, List<Line> lines, Map<String, Integer> counts, Inner inner) {}

    record Plain(String name, String nothing) {}

    @Test
    void splitFieldsBecomeOneRecordPerElement() {
        Outer o = new Outer(
                "x",
                null,
                List.of(new Line(1, "a"), new Line(2, "b")),
                new LinkedHashMap<>(Map.of("k", 3)),
                new Inner("bob", List.of(new Line(9, "z"))));
        Map<String, Object> parts = Parts.of(o)
                .list("lines", (Line l) -> l.id())
                .map("counts")
                .nest("inner")
                .list("inner.lines", (Line l) -> l.id())
                .toMap();

        Map<String, Object> want = new LinkedHashMap<>();
        want.put("name", "x");
        want.put("lines[]", 0);
        want.put("lines[]/1", new Line(1, "a"));
        want.put("lines[]/2", new Line(2, "b"));
        want.put("counts{}", 0);
        want.put("counts/k", 3);
        want.put("inner.", 0);
        want.put("inner.bot", "bob");
        want.put("inner.lines[]", 0);
        want.put("inner.lines[]/9", new Line(9, "z"));
        assertEquals(want, parts, "a null field of a NON_NULL record is left out, as Jackson leaves it out");
    }

    @Test
    void nullsStayWhereJacksonWritesThem() {
        Map<String, Object> parts = Parts.of(new Plain("p", null)).toMap();
        Map<String, Object> want = new LinkedHashMap<>();
        want.put("name", "p");
        want.put("nothing", null);
        assertEquals(want, parts);
    }

    @Test
    void repeatedKeysAreKeptApart() {
        record Bag(List<Line> lines) {}
        Map<String, Object> parts = Parts.of(new Bag(List.of(new Line(1, "a"), new Line(1, "b"))))
                .list("lines", (Line l) -> l.id())
                .toMap();
        assertEquals(new Line(1, "a"), parts.get("lines[]/1"));
        assertEquals(new Line(1, "b"), parts.get("lines[]/1~2"));
    }
}
