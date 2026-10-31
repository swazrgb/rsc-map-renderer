package openrsc.bot.api.container;

/**
 * The empty {@link Inventory} and {@link Bank}. They are not constants on those interfaces because
 * building one initializes {@code Frozen}, which first initializes its superinterface: two threads
 * starting from either end would deadlock.
 */
final class EmptyContainers {

    static final Inventory INVENTORY = new MutableInventory().snapshot();
    static final Bank BANK = new MutableBank().snapshot();

    private EmptyContainers() {}
}
