package openrsc.nav;

import openrsc.bot.api.container.Inventory;

/**
 * One bank stop worked out by {@link LoadoutSpec#settleAt}: what the stop does and the pack it
 * leaves.
 *
 * @param satisfiable false when this stop cannot meet the loadout: a min the pack and bank together
 *     cannot fill, or mins that do not fit in the pack. The manifest is then a best effort.
 * @param projected the pack after {@code manifest} is carried out
 */
public record Settle(boolean satisfiable, Manifest manifest, Inventory projected) {}
