package openrsc.bot.api;

import openrsc.gamedata.geometry.Targetable;

public interface Attackable extends Targetable {

    int serverIndex();
}
