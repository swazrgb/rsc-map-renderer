package openrsc.nav.transports.spec;

import java.util.function.Predicate;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.WorldProfile;

/**
 * Which worlds a declaration exists on, read from the world's configuration. It is settled when the
 * registry is built: a declaration a world does not admit is absent there, unlike an account gate.
 */
@FunctionalInterface
public interface WorldGate {

    boolean admits(WorldProfile world);

    static WorldGate feature(WorldProfile.Feature feature) {
        return world -> world.has(feature);
    }

    static WorldGate rule(Predicate<ServerRules> rule) {
        return world -> rule.test(world.rules());
    }

    /** The worlds whose item id ceiling, {@link ServerRules#maxItemId()}, lets {@code itemId} exist. */
    static WorldGate item(int itemId) {
        return rule(rules -> rules.maxItemId() == ServerRules.UNRESTRICTED || rules.maxItemId() >= itemId);
    }

    /** The worlds whose {@code location_data} setting, which picks the extra loc files loaded, is {@code value}. */
    static WorldGate locationData(int value) {
        return world -> world.locationData() == value;
    }
}
