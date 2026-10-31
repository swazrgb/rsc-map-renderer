package openrsc.bot.api.container;

import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.ItemRef;

/** An occupied slot of a {@link Container}. */
public interface Slot extends ItemRef {

    /** This slot's index in its container, which equals its position in {@link Container#slots()}. */
    int slot();

    ItemId item();

    int amount();

    @Override
    default int id() {
        return item().id();
    }
}
