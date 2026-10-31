package openrsc.bot.api.container;

/** Which copies of an item an {@link Inventory} query counts. Without bank notes all three agree. */
public enum NoteForm {
    /** The item itself, the copies that can be used; the default of every form-less query. */
    UNNOTED,

    /** Its bank notes only. */
    NOTED,

    /** Every copy, noted or not. */
    BOTH;

    public boolean admits(boolean noted) {
        return switch (this) {
            case UNNOTED -> !noted;
            case NOTED -> noted;
            case BOTH -> true;
        };
    }

    public boolean admits(InventorySlot slot) {
        return admits(slot.noted());
    }
}
