package openrsc.bot.api;

import openrsc.gamedata.geometry.Targetable;

public record WallObject(int id, int x, int z, int dir) implements Targetable {

    @Override
    public Targetable.Kind kind() {
        return Targetable.Kind.BOUNDARY;
    }
}
