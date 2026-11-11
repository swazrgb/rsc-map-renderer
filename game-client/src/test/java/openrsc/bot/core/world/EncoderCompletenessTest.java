package openrsc.bot.core.world;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EncoderCompletenessTest {

    private static final Map<String, String> WHITELIST = new LinkedHashMap<>();

    static {
        WHITELIST.put("SEND_INVENTORY_UPDATEITEM", "COVERED: full SEND_INVENTORY re-emitted on change");
        WHITELIST.put("SEND_INVENTORY_REMOVEITEM", "COVERED: full SEND_INVENTORY re-emitted on change");
        WHITELIST.put("SEND_EQUIPMENT_UPDATE", "COVERED: full SEND_EQUIPMENT re-emitted on change");
        WHITELIST.put(
                "SEND_EXPERIENCE",
                "COVERED: full SEND_STATS re-emitted on change (silent xp set, "
                        + "no drop/level-up side effect — unlike SEND_STAT/159, which we do emit)");
        WHITELIST.put("SEND_BANK_UPDATE", "COVERED: full SEND_BANK_OPEN re-emitted on open/change");
        WHITELIST.put(
                "SEND_UPDATE_IGNORE_LIST_BECAUSE_NAME_CHANGE",
                "TRANSIENT: client handler is a rare name-change event/message, not an ignore-list delta");

        WHITELIST.put("SEND_28_BYTES_UNUSED", "NO-OP: legacy/ignored packet, carries no observable state");

        WHITELIST.put(
                "SEND_WELCOME_INFO",
                "TRANSIENT: one-time login welcome popup, already dismissed before a viewer attaches");
        WHITELIST.put("SEND_DEATH", "TRANSIENT: death event; combat + damage splats already visible");
    }

    @Test
    void everyDecodedStateOpcodeIsEmittedOrWhitelisted() throws Exception {
        assertTrue(
                opcodeValues().size() > 50,
                "opcode table came back empty — the reflection over "
                        + "Opcode broke and this guard would silently pass on nothing");
        Map<String, Integer> value = opcodeValues();
        Set<Integer> decoded = values(caseOpcodes(readSource("PacketDispatcher.java"), value.keySet()), value);
        Set<Integer> emitted = values(allOpcodes(readSource("WorldSnapshotEncoder.java"), value.keySet()), value);

        Set<Integer> missing = new TreeSet<>(decoded);
        missing.removeAll(emitted);
        missing.removeAll(values(WHITELIST.keySet(), value));
        if (!missing.isEmpty()) {
            fail("Decoded opcodes the encoder never emits and nobody justified — an observer is missing "
                    + "this state (as it was with the quest table). Emit each in WorldSnapshotEncoder, or add a "
                    + "categorised WHITELIST entry in this test:\n  " + describe(missing, value));
        }

        for (String name : WHITELIST.keySet()) {
            assertTrue(value.containsKey(name), "WHITELIST names a non-opcode: " + name);
            int v = value.get(name);
            assertTrue(decoded.contains(v), "WHITELIST entry " + name + " is not decoded by the dispatcher");
            assertTrue(
                    !emitted.contains(v),
                    "WHITELIST entry " + name + " IS emitted (by value) — remove it from the whitelist");
        }
    }

    private static final Pattern OPCODE_REF = Pattern.compile("Opcode\\.([A-Z0-9_]+)");
    private static final Pattern CASE_LABEL = Pattern.compile("case\\b(.*?)->", Pattern.DOTALL);

    private static Set<String> caseOpcodes(String src, Set<String> valid) {
        Set<String> found = new TreeSet<>();
        Matcher label = CASE_LABEL.matcher(src);
        while (label.find()) {
            addOpcodes(label.group(1), valid, found);
        }
        return found;
    }

    private static Set<String> allOpcodes(String src, Set<String> valid) {
        Set<String> found = new TreeSet<>();
        addOpcodes(src, valid, found);
        return found;
    }

    private static void addOpcodes(String text, Set<String> valid, Set<String> into) {
        Matcher m = OPCODE_REF.matcher(text);
        while (m.find()) {
            if (valid.contains(m.group(1))) {
                into.add(m.group(1));
            }
        }
    }

    private static Set<Integer> values(Set<String> names, Map<String, Integer> value) {
        return names.stream().map(value::get).collect(Collectors.toCollection(TreeSet::new));
    }

    private static String describe(Set<Integer> missing, Map<String, Integer> value) {
        return missing.stream()
                .map(v -> value.entrySet().stream()
                                .filter(e -> e.getValue().equals(v))
                                .map(Map.Entry::getKey)
                                .collect(Collectors.joining("/"))
                        + " (" + v + ")")
                .collect(Collectors.joining("\n  "));
    }

    private static Map<String, Integer> opcodeValues() {
        Map<String, Integer> names = new LinkedHashMap<>();
        for (Opcode o : Opcode.values()) {
            if (o.dir() == Opcode.Dir.IN) {
                names.put(o.name(), o.wire());
            }
        }
        return names;
    }

    private static String readSource(String simpleName) throws Exception {
        Path testClasses = Path.of(EncoderCompletenessTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path moduleDir = testClasses;
        while (moduleDir != null && !Files.isDirectory(moduleDir.resolve("src/main/java"))) {
            moduleDir = moduleDir.getParent();
        }
        if (moduleDir == null) {
            throw new IllegalStateException("no module directory above " + testClasses);
        }
        Path src = moduleDir.resolve("src/main/java/openrsc/bot/core/world/" + simpleName);
        return Files.readString(src);
    }
}
