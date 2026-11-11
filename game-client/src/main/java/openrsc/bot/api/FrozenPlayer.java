package openrsc.bot.api;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.Nullable;

public record FrozenPlayer(
        int serverIndex,
        @Nullable String username,
        int x,
        int z,
        int sprite,
        Appearance appearance,
        int currentHits,
        int maxHits,
        int lastDamage,
        long lastDamageTick,
        @Nullable String lastChat,
        long lastChatTick,
        int actionBubbleItemId,
        long actionBubbleTick,
        long retreatTick,
        int stepDir,
        List<Point> trail)
        implements Player {

    @Override
    public int combatLevel() {
        return appearance.combatLevel();
    }

    @Override
    public boolean skulled() {
        return appearance.skulled();
    }

    @Override
    public @Nullable String appearanceToken() {
        return appearance.token();
    }

    @Override
    public String toString() {
        return "Player{idx=" + serverIndex + (username != null ? " " + username : "") + " @(" + x + "," + z + ") spr="
                + sprite + "}";
    }
}
