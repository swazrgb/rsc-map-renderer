package openrsc.gamedata;

/**
 * Anything that names an item by server template id — {@link ItemId} constants, strategy records
 * (food, pickaxes, tackle), etc. {@code Bot} inventory / bank / shop primitives accept these
 * directly so call sites read {@code bot.withdrawItem(ItemId.COINS, 40)} instead of threading
 * {@code .id()} everywhere.
 */
public interface ItemRef {

  /**
   * Server item template id ({@code com.openrsc.server.constants.ItemId}).
   */
  int id();
}
