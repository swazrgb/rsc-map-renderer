package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import org.junit.jupiter.api.Test;

/** Every stop a settle works out keeps its own contract, over seeded stops the game could present. */
class SettleContractTest {

    private static final int CASES = 20_000;

    @Test
    void uranium_stops_keep_their_contract() {
        holds(RealDefs.serverData(), 1);
    }

    @Test
    void coleslaw_stops_keep_their_contract() {
        holds(RealDefs.serverData(RealDefs.COLESLAW), 2);
    }

    private static void holds(ServerData defs, long salt) {
        Map<String, List<String>> broken = new LinkedHashMap<>();
        for (int i = 0; i < CASES; i++) {
            SettleCases c = SettleCases.generate(salt * 1_000_003L + i, defs);
            Settle settle = c.spec().settle(c.pack(), c.bank(), defs, c.keepFree());
            String why = c.spec().settleViolation(c.pack(), c.bank(), c.keepFree(), settle, defs);
            if (why != null) {
                String kind =
                        why.replaceAll("[0-9]+", "N").replaceAll("\\[.*", "").replaceAll(" [A-Z_]{3,}", " X");
                broken.computeIfAbsent(kind, k -> new ArrayList<>()).add(why + " — " + settle.manifest() + " — " + c);
            }
        }
        StringBuilder report = new StringBuilder();
        broken.forEach((kind, cases) -> report.append('\n')
                .append(cases.size())
                .append(" × ")
                .append(kind)
                .append("\n    e.g. ")
                .append(cases.getFirst()));
        assertTrue(broken.isEmpty(), report.toString());
    }
}
