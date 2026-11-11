package openrsc.bot.api;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import org.jetbrains.annotations.Nullable;

public interface Player extends Attackable, Targetable {

    @Override
    int serverIndex();

    @Override
    default int id() {
        return -1;
    }

    @Override
    default Targetable.Kind kind() {
        return Targetable.Kind.PLAYER;
    }

    default Targetable asTradeTarget() {
        return new PlayerTradeTarget(this);
    }

    default Targetable asUseTarget() {
        return new PlayerUseTarget(this);
    }

    String username();

    default String appearanceToken() {
        return null;
    }

    @Override
    int x();

    @Override
    int z();

    int sprite();

    default int stepDir() {
        return -1;
    }

    default List<Point> trail() {
        return List.of(point());
    }

    default @Nullable Point steppingTo() {
        int dir = stepDir();
        return dir < 0 ? null : new Point(x() + stepDx(dir), z() + stepDz(dir));
    }

    static int stepDirOf(int dx, int dz) {
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
            return -1;
        }
        for (int dir = 0; dir < 8; dir++) {
            if (stepDx(dir) == dx && stepDz(dir) == dz) {
                return dir;
            }
        }
        return -1;
    }

    static int stepDx(int dir) {
        return dir >= 1 && dir <= 3 ? 1 : dir >= 5 && dir <= 7 ? -1 : 0;
    }

    static int stepDz(int dir) {
        return dir >= 3 && dir <= 5 ? 1 : dir == 7 || dir == 0 || dir == 1 ? -1 : 0;
    }

    default boolean inCombat() {
        return sprite() == 8 || sprite() == 9;
    }

    int combatLevel();

    default boolean skulled() {
        return false;
    }

    default int currentHits() {
        return 0;
    }

    default int maxHits() {
        return 0;
    }

    default int lastDamage() {
        return -1;
    }

    default long lastDamageTick() {
        return -1;
    }

    default String lastChat() {
        return null;
    }

    default long retreatTick() {
        return Long.MIN_VALUE;
    }

    default long lastChatTick() {
        return -1;
    }

    default Appearance appearance() {
        return Appearance.UNKNOWN;
    }

    int actionBubbleItemId();

    long actionBubbleTick();

    @Override
    default Point point() {
        return new Point(x(), z());
    }
}
