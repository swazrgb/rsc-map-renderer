package openrsc.gamedata.geometry;

/** The tiles a teleport works from: below {@code wildernessLimit} and outside {@link TeleportBlocks}. */
public record TeleportRegion(int wildernessLimit) {

    /** Teleport spells. */
    public static final TeleportRegion SPELLS = new TeleportRegion(TeleportBlocks.SPELL_WILDERNESS_LIMIT);

    /** Item teleports, such as a charged dragonstone amulet. */
    public static final TeleportRegion ITEMS = new TeleportRegion(TeleportBlocks.ITEM_WILDERNESS_LIMIT);

    public boolean contains(int x, int z) {
        return TeleportBlocks.castable(x, z, wildernessLimit);
    }
}
