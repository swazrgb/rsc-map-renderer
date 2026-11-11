package openrsc.bot.core.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.AuctionHouse;
import openrsc.bot.api.GameObject;
import openrsc.bot.api.WallObject;
import org.jetbrains.annotations.Nullable;

public final class ViewerState {

    @Nullable
    List<AuctionHouse.Listing> auctionSent;

    final Map<Long, GameObject> scenery = new HashMap<>();
    final Map<Long, WallObject> walls = new HashMap<>();
    final Map<Long, Integer> ground = new HashMap<>();

    final List<Integer> knownPlayers = new ArrayList<>();
    final Map<Integer, int[]> playerLast = new HashMap<>();
    final List<Integer> knownNpcs = new ArrayList<>();
    final Map<Integer, int[]> npcLast = new HashMap<>();

    final Map<String, byte[]> lastSent = new HashMap<>();

    final Map<Integer, byte[]> lastAppearance = new HashMap<>();

    int[] lastSkillCur;
    int[] lastSkillBase;
    long[] lastSkillXp;
    int lastQuestPoints = -1;

    static long tileKey(int x, int z) {
        return ((long) (x & 0xFFFF) << 16) | (z & 0xFFFF);
    }

    static long wallKey(int x, int z, int dir) {
        return (tileKey(x, z) << 3) | (dir & 0x7);
    }

    static long groundKey(int x, int z, int id) {
        return ((long) (id & 0xFFFF) << 32) | ((long) (x & 0xFFFF) << 16) | (z & 0xFFFF);
    }

    static int groundKeyX(long key) {
        return (int) ((key >> 16) & 0xFFFF);
    }

    static int groundKeyZ(long key) {
        return (int) (key & 0xFFFF);
    }

    static int groundKeyId(long key) {
        return (int) ((key >> 32) & 0xFFFF);
    }

    static long chunkOfTileKey(long tileKey) {
        int x = (int) ((tileKey >> 16) & 0xFFFF);
        int z = (int) (tileKey & 0xFFFF);
        return ((long) (x >> 3) << 32) | ((z >> 3) & 0xFFFFFFFFL);
    }

    static long chunkOfWallKey(long wallKey) {
        return chunkOfTileKey(wallKey >> 3);
    }

    static long chunkOfGroundKey(long groundKey) {
        int x = (int) ((groundKey >> 16) & 0xFFFF);
        int z = (int) (groundKey & 0xFFFF);
        return ((long) (x >> 3) << 32) | ((z >> 3) & 0xFFFFFFFFL);
    }
}
