package openrsc.bot.api;

import openrsc.gamedata.geometry.Targetable;

public record PlayerUseTarget(Player player) implements Targetable {
    @Override
    public int x() {
        return player.x();
    }

    @Override
    public int z() {
        return player.z();
    }

    @Override
    public int id() {
        return -1;
    }

    @Override
    public Kind kind() {
        return Kind.PLAYER_USE;
    }
}
