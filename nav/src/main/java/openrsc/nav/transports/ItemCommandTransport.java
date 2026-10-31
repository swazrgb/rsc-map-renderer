package openrsc.nav.transports;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.TeleportRegion;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * An inventory item's own command that moves the player to a fixed tile, optionally answering the
 * menu it opens and spending items. A regional one ({@link #teleport}) fires from any tile of
 * {@link TeleportRegion#ITEMS} and lists no entries; a place-bound one fires only from its entries.
 */
public final class ItemCommandTransport implements Transport, DeclarativeTransport {

    private String name;
    private String placementId;
    private final int itemId;
    private final List<Point> entries;
    private final Set<Point> entrySet;
    private final Point to;
    private final int cost;
    private final Prereq prereq;
    private final List<String[]> menus;
    private final List<Consumed> consumes;
    private final boolean regional;

    private String note;

    public static ItemCommandTransport teleport(int itemId, Point to, int cost, Prereq prereq, List<String[]> menus) {
        return new ItemCommandTransport(itemId, true, List.of(), to, cost, prereq, menus, List.of());
    }

    /** Fired only from {@code entries}, spending {@code consumes} on each use; throws when {@code entries} are empty. */
    public static ItemCommandTransport spendingAt(
            int itemId,
            List<Point> entries,
            Point to,
            int cost,
            Prereq prereq,
            List<String[]> menus,
            List<Consumed> consumes) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a place-bound item use needs at least one entry tile");
        }
        return new ItemCommandTransport(itemId, false, entries, to, cost, prereq, menus, consumes);
    }

    private ItemCommandTransport(
            int itemId,
            boolean regional,
            List<Point> entries,
            Point to,
            int cost,
            Prereq prereq,
            List<String[]> menus,
            List<Consumed> consumes) {
        this.regional = regional;
        this.itemId = itemId;
        this.entries = List.copyOf(entries);
        this.entrySet = new HashSet<>(entries);
        this.to = to;
        this.cost = cost;
        this.prereq = prereq;
        this.menus = List.copyOf(menus);
        this.consumes = List.copyOf(consumes);
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return consumes;
    }

    @Override
    public Prereq prereq() {
        return prereq;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return regional ? TeleportRegion.ITEMS.contains(x, y) : entrySet.contains(Point.of(x, y));
    }

    @Override
    public List<Point> entries() {
        return entries;
    }

    @Override
    public @Nullable TeleportRegion region() {
        return regional ? TeleportRegion.ITEMS : null;
    }

    @Override
    public Point destinationFrom(Point from) {
        return to;
    }

    @Override
    public int cost() {
        return cost;
    }

    public ItemCommandTransport withNote(String note) {
        this.note = note;
        return this;
    }

    @Override
    public String note() {
        return note;
    }

    public ItemCommandTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    @Override
    public EngageSpec engageSpec() {
        EngageSpec.Action command = EngageSpec.Action.itemCommand(itemId);
        return menus.isEmpty()
                ? EngageSpec.of(command, EngageSpec.Target.SELF)
                : EngageSpec.of(command, EngageSpec.Target.SELF, menus);
    }
}
