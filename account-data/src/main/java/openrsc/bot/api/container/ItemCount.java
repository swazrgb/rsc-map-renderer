package openrsc.bot.api.container;

import openrsc.gamedata.data.ItemRef;

/** Something that holds items and can say how many of each. */
public interface ItemCount {

    /**
     * How much of {@code itemId} is held. A {@code long}: the server splits a stack that would pass
     * {@link Integer#MAX_VALUE} into a second one, so even one bank can hold more than an int.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L97">Bank.add</a>
     */
    long count(int itemId);

    default long count(ItemRef item) {
        return count(item.id());
    }

    boolean has(int itemId);

    default boolean has(ItemRef item) {
        return has(item.id());
    }

    /** Whether at least {@code amount} is held; true for any {@code amount} of zero or less. */
    default boolean has(ItemRef item, long amount) {
        return count(item) >= amount;
    }

    /** {@code total} narrowed to an int, saturating at the int range instead of wrapping. */
    static int toInt(long total) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, total));
    }
}
