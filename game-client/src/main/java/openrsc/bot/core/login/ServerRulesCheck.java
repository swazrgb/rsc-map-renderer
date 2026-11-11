package openrsc.bot.core.login;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.WorldProfile.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerRulesCheck {

    private static final Logger LOG = LoggerFactory.getLogger(ServerRulesCheck.class);

    private record Field(int number, String key, Predicate<WorldProfile> conf) {}

    private static final List<Field> FIELDS = List.of(
            new Field(4, "spawn_auction_npcs", w -> w.has(Feature.AUCTION_NPCS)),
            new Field(5, "spawn_iron_man_npcs", w -> w.has(Feature.IRONMAN_NPCS)),
            new Field(12, "batch_progression", w -> w.rules().batching()),
            new Field(29, "want_custom_banks", w -> w.rules().customBanks()),
            new Field(31, "want_bank_notes", w -> w.rules().bankNotes()),
            new Field(32, "want_cert_deposit", w -> w.rules().certDeposit()),
            new Field(34, "want_drop_x", w -> w.rules().dropX()),
            new Field(36, "want_woodcutting_guild", w -> w.has(Feature.WOODCUTTING_GUILD)),
            new Field(37, "want_decanting", w -> w.rules().decanting()),
            new Field(38, "want_certer_bank_exchange", w -> w.rules().certerBankExchange()),
            new Field(40, "right_click_bank", w -> w.rules().quickBank()),
            new Field(43, "member_world", WorldProfile::memberWorld),
            new Field(51, "want_fatigue", w -> w.rules().fatigue()),
            new Field(52, "custom_sprites", w -> w.has(Feature.CUSTOM_SPRITES)),
            new Field(58, "fishing_spots_depletable", w -> w.rules().fishingSpotsDeplete()),
            new Field(60, "want_runecraft", w -> w.has(Feature.RUNECRAFT)),
            new Field(61, "custom_landscape", w -> w.has(Feature.CUSTOM_LANDSCAPE)),
            new Field(62, "want_equipment_tab", w -> w.rules().equipmentTab()),
            new Field(63, "want_bank_presets", w -> w.rules().bankPresets()),
            new Field(65, "mining_rocks_extended", w -> w.rules().extendedMiningRocks()),
            new Field(67, "want_leftclick_webs", w -> w.rules().leftClickWebs()),
            new Field(73, "want_harvesting", w -> w.has(Feature.HARVESTING)),
            new Field(76, "right_click_trade", w -> w.rules().quickTrade()),
            new Field(77, "features_sleep", w -> w.rules().sleeping()),
            new Field(80, "want_openpk_points", w -> w.has(Feature.OPENPK_POINTS)));

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private ServerRulesCheck() {}

    public static List<String> mismatches(WorldProfile world, PreLoginConfigs sent) {
        List<String> out = new ArrayList<>();
        for (Field f : FIELDS) {
            Integer value = sent.flags().get(f.number());
            if (value == null) {
                continue;
            }
            boolean server = value != 0;
            if (server != f.conf().test(world)) {
                out.add(f.key() + "=" + server);
            }
        }
        return out;
    }

    public static void verify(WorldProfile world, PreLoginConfigs sent) {
        List<String> wrong = mismatches(world, sent);
        if (wrong.isEmpty()) {
            return;
        }
        String line = "WORLD: server is not " + world.name() + ": " + String.join(" ", wrong);
        if (LOGGED.add(line)) {
            LOG.error(line);
        }
    }
}
