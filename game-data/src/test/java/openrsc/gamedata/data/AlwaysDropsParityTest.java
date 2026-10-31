package openrsc.gamedata.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import openrsc.gamedata.ServerConf;
import org.junit.jupiter.api.Test;

/**
 * Reads the server's {@code NpcDrops.generateNpcDrops} from its source and checks {@link AlwaysDrops}
 * against every table's items of weight 0. Skipped when the server tree is not there.
 */
class AlwaysDropsParityTest {

    private static final Pattern NEW = Pattern.compile("^(?:DropTable\\s+)?(\\w+)\\s*=\\s*new DropTable\\(");
    private static final Pattern CLONE = Pattern.compile("^(?:DropTable\\s+)?(\\w+)\\s*=\\s*(\\w+)\\.clone\\(");
    private static final Pattern ADD = Pattern.compile("^(\\w+)\\.addItemDrop\\((.*)\\);");
    private static final Pattern PUT =
            Pattern.compile("this\\.npcDrops\\.put\\(NpcId\\.(\\w+)\\.id\\(\\),\\s*(\\w+)\\)");
    private static final Pattern ITEM = Pattern.compile("ItemId\\.(\\w+)\\.id\\(\\)");

    private static Path source() {
        try {
            Path p = ServerConf.resolve().serverSrc().resolve("constants/NpcDrops.java");
            return Files.isRegularFile(p) ? p : null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** Each NPC's weight-0 drops as {@code ITEM x amount}, by NPC name, from the server's tables. */
    private static Map<String, List<String>> parse(String src) {
        int start = src.indexOf("private void generateNpcDrops()");
        assertTrue(start >= 0, "server NpcDrops.java has no generateNpcDrops");
        int end = src.indexOf("\n\tprivate void ", start + 1);
        Map<String, List<String>> vars = new HashMap<>();
        Map<String, List<String>> byNpc = new TreeMap<>();
        int depth = 0;
        for (String raw : src.substring(start, end < 0 ? src.length() : end).split("\n")) {
            String line = raw.strip();
            if (line.startsWith("//")) {
                continue;
            }
            Matcher created = NEW.matcher(line);
            Matcher cloned = CLONE.matcher(line);
            Matcher added = ADD.matcher(line);
            Matcher put = PUT.matcher(line);
            if (created.find()) {
                vars.put(created.group(1), new ArrayList<>());
            } else if (cloned.find()) {
                vars.put(cloned.group(1), new ArrayList<>(vars.get(cloned.group(2))));
            } else if (added.find()) {
                List<String> args = List.of(added.group(2).split(",\\s*"));
                if (args.get(2).strip().equals("0")) {
                    Matcher item = ITEM.matcher(args.get(0));
                    assertTrue(item.matches(), "an always-drop of no plain item: " + line);
                    assertEquals(1, depth, "an always-drop inside a branch: " + line);
                    vars.get(added.group(1))
                            .add(item.group(1) + " x "
                                    + Integer.parseInt(args.get(1).strip()));
                }
            } else if (put.find()) {
                byNpc.put(put.group(1), vars.get(put.group(2)));
            } else {
                assertFalse(
                        line.contains("addItemDrop(")
                                || line.contains("npcDrops.put(")
                                || line.matches(".*=\\s*\\w+\\.clone\\(.*"),
                        "a table line the copy does not read: " + line);
            }
            depth += (int) line.chars().filter(c -> c == '{').count();
            depth -= (int) line.chars().filter(c -> c == '}').count();
        }
        byNpc.values().removeIf(List::isEmpty);
        return byNpc;
    }

    @Test
    void the_copy_is_the_servers_drop_tables() throws Exception {
        Path src = source();
        assumeTrue(src != null, "no server tree");
        Map<String, List<String>> copy = new TreeMap<>();
        AlwaysDrops.all()
                .forEach((npc, drops) -> copy.put(
                        NpcId.getById(npc).name(),
                        drops.stream()
                                .map(d -> d.item().name() + " x " + d.amount())
                                .toList()));
        assertEquals(parse(Files.readString(src)), copy);
    }

    @Test
    void both_unicorns_always_drop_a_horn() {
        assertEquals(Set.of(NpcId.UNICORN.id(), NpcId.BLACK_UNICORN.id()), AlwaysDrops.droppers(ItemId.UNICORN_HORN));
        assertEquals(List.of(), AlwaysDrops.of(NpcId.GOBLIN_LVL13.id()));
    }
}
