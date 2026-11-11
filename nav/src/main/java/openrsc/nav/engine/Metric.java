package openrsc.nav.engine;

/** A {@link Routing} weighed for one routing class. Immutable once built; safe for concurrent use. */
interface Metric {

    /** What a byte-budgeted store weighs this by. */
    long bytes();

    /**
     * The packed cost ({@link PackedCost}) from each rank of a bank row to bank {@code i}, at
     * {@code [slot * banks + i]} with {@code slot} from {@link Routing#bankSlots}; {@link PackedCost#INF} where it
     * does not reach. Meeting a search's forward labels at these rows prices every bank. Shared: never write it.
     */
    long[] toBankLabels();

    /** The cost from bank {@code i} to each rank of a bank row, laid out as {@link #toBankLabels}. */
    long[] fromBankLabels();
}
