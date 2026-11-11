package openrsc.bot.api;

import openrsc.gamedata.geometry.Targetable;

public record GroundItem(int id, int x, int z, boolean noted) implements Targetable {

    public GroundItem(int id, int x, int z) {
        this(id, x, z, false);
    }

    @Override
    public Targetable.Kind kind() {
        return Targetable.Kind.GROUND;
    }
}
