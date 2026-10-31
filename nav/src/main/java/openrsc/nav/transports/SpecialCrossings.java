package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Bespoke.bespoke;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.WorldGate.feature;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/** Crossings built by a transport class of their own rather than from a shared spec. */
public enum SpecialCrossings implements TransportDef {
    AL_KHARID_BORDER(bespoke((env, id) -> List.of(
            AlKharidGateTransport.tollWestward().withPlacement(id),
            AlKharidGateTransport.tollEastward().withPlacement(id),
            AlKharidGateTransport.freeWestward().withPlacement(id),
            AlKharidGateTransport.freeEastward().withPlacement(id)))),
    SHANTAY_PASS_INTO_DESERT(bespoke(ShantayPassTransport::forWorld)),
    SHANTAY_PASS_TO_AL_KHARID(bespoke((env, id) -> List.of(new ShantayPassReturnTransport().withPlacement(id)))),

    BANANA_ESCAPE_TO_PORT_SARIM(
            bespoke((env, id) -> List.of(new BananaPlantationTransport(269, 648).withPlacement(id)))),
    BANANA_ESCAPE_TO_ARDOUGNE(bespoke((env, id) -> List.of(new BananaPlantationTransport(538, 617).withPlacement(id)))),

    // Any charge-orb spell cast on the gate moves the player to (474,3714), from either side.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestDarkMetalGate.java#L97
    LEGENDS_DARK_METAL_GATE_CHARGE_ORB_S_RETURN(bespoke((env, id) -> List.of(new SpellLocTransport(
                    29,
                    56,
                    new SpellTransport.RuneCost(611, 1, 32, 30, 46, 3),
                    1165,
                    474,
                    3719,
                    474,
                    3719,
                    474,
                    3714,
                    90)
            .withPlacement(id)))),

    ARHEIN_STOW_AWAY_SHIP_CATHERBY_DOCK_KEEP_LE_FAYE(
            bespoke((env, id) -> List.of(new KeepLeFayeShipTransport().withPlacement(id)))),

    SCOT_RUTH_TUNNEL_BUY(
            onlyIn(feature(WorldProfile.Feature.CUSTOM_QUESTS), bespoke(ScotRuthTunnelTransport::forWorld))),

    ERNEST_BASEMENT_MAZE(bespoke(ErnestBasementMazeTransport::forWorld)),
    ;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    SpecialCrossings(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (SpecialCrossings c : values()) {
            out.addAll(c.expand(env));
        }
        return out;
    }
}
