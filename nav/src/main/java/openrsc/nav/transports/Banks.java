package openrsc.nav.transports;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.Location;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.BankSite;

/**
 * The bank stops the engine plans through: one {@link BankSite} per {@link Location.Kind#BANK}
 * entry, its tile a reachable tile beside a banker (or a bank chest). A bank some worlds lack, such
 * as a custom world's guild chest, is a bank only on the worlds that have it, so planning asks for
 * one world's banks; {@link #ALL} is every bank of every world.
 */
public final class Banks {

    /** A stop's cost in deci-ticks: the walk up to the banker, the dialogue and the withdraw. */
    private static final int DEFAULT_COST = 80;

    // List.copyOf, which NavInputs' own copy hands back unchanged: the engine caches its cluster graph
    // by the bank list's identity, so two engines over one world share one graph.
    public static final List<BankSite> ALL = List.copyOf(Location.ofKind(Location.Kind.BANK).stream()
            .map(l -> new BankSite(l.label(), l.point(), costFor(l)))
            .toList());

    private static final Map<ServerData, World> WORLDS = new WeakHashMap<>();

    private record World(List<BankSite> banks, List<Point> tiles, Dest any) {
        static World of(List<BankSite> banks) {
            List<Point> tiles = List.copyOf(banks.stream().map(BankSite::tile).toList());
            return new World(banks, tiles, Dest.anyOf(tiles.toArray(new Area[0])));
        }
    }

    private static final World EVERY = World.of(ALL);

    private static World world(ServerData world) {
        synchronized (WORLDS) {
            return WORLDS.computeIfAbsent(world, w -> {
                List<Location> here =
                        Location.BANKS.stream().filter(l -> l.existsIn(w)).toList();
                if (here.size() == Location.BANKS.size()) {
                    return EVERY;
                }
                return World.of(List.copyOf(ALL.stream()
                        .filter(b -> here.stream().anyMatch(l -> l.label().equals(b.name())))
                        .toList()));
            });
        }
    }

    /**
     * The banks {@code world} has, in {@link #ALL}'s order. The same list instance for every call
     * with one world. Thread-safe.
     */
    public static List<BankSite> on(ServerData world) {
        return world(world).banks();
    }

    /** The tiles of {@link #on}, in its order. */
    public static List<Point> tiles(ServerData world) {
        return world(world).tiles();
    }

    /** Any bank of {@link #on} as one goal: whichever tile is cheapest to reach. */
    public static Dest any(ServerData world) {
        return world(world).any();
    }

    private Banks() {}

    /**
     * {@code bank}'s site, whether or not a given world has it.
     *
     * @throws IllegalArgumentException when {@code bank} is not a bank
     */
    public static BankSite of(Location bank) {
        for (BankSite b : ALL) {
            if (b.name().equals(bank.label())) {
                return b;
            }
        }
        throw new IllegalArgumentException(bank + " is not a bank");
    }

    private static int costFor(Location bank) {
        return switch (bank) {
            default -> DEFAULT_COST;
        };
    }
}
