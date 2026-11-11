package openrsc.bot.api;

import openrsc.gamedata.geometry.Targetable;

public class PlayerTradeTarget implements Targetable {

    private final Player self;

    public PlayerTradeTarget(Player self) {
        this.self = self;
    }

    @Override
    public int x() {
        return self.x();
    }

    @Override
    public int z() {
        return self.z();
    }

    @Override
    public int id() {
        return -1;
    }

    @Override
    public Kind kind() {
        return Kind.PLAYER_TRADE;
    }

    @Override
    public String toString() {
        return "trade with " + self.username() + " @(" + self.x() + "," + self.z() + ")";
    }
}
