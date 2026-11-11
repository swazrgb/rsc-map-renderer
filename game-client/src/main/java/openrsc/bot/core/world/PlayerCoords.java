package openrsc.bot.core.world;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.Player;
import openrsc.bot.core.codec.RscPackets;
import org.jetbrains.annotations.Nullable;

final class PlayerCoords {

    static final byte KEEP = 0;
    static final byte SPRITE = 1;
    static final byte REMOVE = 2;
    static final byte WALK = 3;

    static final int SPAWN_BITS = 27;

    record Spawn(int serverIndex, int dx, int dz, int sprite) {}

    record Decoded(int count, int wireCount, byte[] kind, byte[] value, List<Spawn> spawns) {

        Set<Integer> resent() {
            Set<Integer> out = new HashSet<>(spawns.size() * 2);
            for (Spawn s : spawns) {
                out.add(s.serverIndex());
            }
            return out;
        }
    }

    private PlayerCoords() {}

    static @Nullable Decoded decode(ByteBuf body, int ofs, int totalBits, int wireCount, int held) {
        int nearest = wireCount + 256 * Math.max(0, Math.round((held - wireCount) / 256f));
        Decoded best = tryDecode(body, ofs, totalBits, nearest, wireCount);
        if (best != null) {
            return best;
        }
        for (int count = wireCount; count <= totalBits - ofs; count += 256) {
            Decoded d = count == nearest ? null : tryDecode(body, ofs, totalBits, count, wireCount);
            if (d != null && (best == null || Math.abs(count - held) < Math.abs(best.count() - held))) {
                best = d;
            }
        }
        return best;
    }

    private static @Nullable Decoded tryDecode(ByteBuf body, int ofs, int totalBits, int count, int wireCount) {
        byte[] kind = new byte[count];
        byte[] value = new byte[count];
        for (int i = 0; i < count; i++) {
            if (ofs + 1 > totalBits) {
                return null;
            }
            if (RscPackets.readBits(body, ofs++, 1) == 0) {
                kind[i] = KEEP;
                continue;
            }
            if (ofs + 1 > totalBits) {
                return null;
            }
            if (RscPackets.readBits(body, ofs++, 1) == 0) {
                if (ofs + 3 > totalBits) {
                    return null;
                }
                kind[i] = WALK;
                value[i] = (byte) RscPackets.readBits(body, ofs, 3);
                ofs += 3;
                continue;
            }
            if (ofs + 2 > totalBits) {
                return null;
            }
            int high = RscPackets.readBits(body, ofs, 2);
            ofs += 2;
            if (high == 3) {
                kind[i] = REMOVE;
                continue;
            }
            if (ofs + 2 > totalBits) {
                return null;
            }
            kind[i] = SPRITE;
            value[i] = (byte) ((high << 2) | RscPackets.readBits(body, ofs, 2));
            ofs += 2;
        }
        int left = totalBits - ofs;
        if (left % SPAWN_BITS >= 8) {
            return null;
        }
        List<Spawn> spawns = new ArrayList<>(left / SPAWN_BITS);
        Set<Integer> seen = new HashSet<>();
        for (int n = left / SPAWN_BITS; n > 0; n--) {
            int index = RscPackets.readBits(body, ofs, 11);
            int dx = RscPackets.readBits(body, ofs + 11, 6);
            int dz = RscPackets.readBits(body, ofs + 17, 6);
            int sprite = RscPackets.readBits(body, ofs + 23, 4);
            ofs += SPAWN_BITS;
            if (!seen.add(index)) {
                return null;
            }
            spawns.add(new Spawn(index, dx > 31 ? dx - 64 : dx, dz > 31 ? dz - 64 : dz, sprite));
        }
        return new Decoded(count, wireCount, kind, value, spawns);
    }

    private static final int SKIP = 4;
    private static final int LEFT_AT_EDGE = 1;
    private static final int LEFT_INSIDE = 3;
    private static final int EDGE = 14;
    private static final int UNEXPLAINED = 4;
    private static final int CONTRADICTION = 10;

    static int[] align(byte[] kind, int count, List<? extends Player> held, Set<Integer> resent, int selfX, int selfZ) {
        int m = held.size();
        boolean[] isResent = new boolean[m];
        int[] leftCost = new int[m];
        Set<Integer> heldIndices = new HashSet<>(m * 2);
        for (int j = 0; j < m; j++) {
            Player p = held.get(j);
            isResent[j] = resent.contains(p.serverIndex());
            heldIndices.add(p.serverIndex());
            int far = Math.max(Math.abs(p.x() - selfX), Math.abs(p.z() - selfZ));
            leftCost[j] = far >= EDGE ? LEFT_AT_EDGE : LEFT_INSIDE;
        }
        if (count == m && consistent(kind, isResent)) {
            int[] identity = new int[count];
            for (int i = 0; i < count; i++) {
                identity[i] = i;
            }
            return identity;
        }
        int skipRemove = heldIndices.containsAll(resent) ? SKIP + UNEXPLAINED : SKIP;
        int w = m + 1;
        int[] cost = new int[(count + 1) * w];
        byte[] from = new byte[(count + 1) * w];
        for (int j = 1; j <= m; j++) {
            cost[j] = j * SKIP;
            from[j] = 2;
        }
        for (int i = 1; i <= count; i++) {
            int skipThis = kind[i - 1] == REMOVE ? skipRemove : SKIP;
            cost[i * w] = cost[(i - 1) * w] + skipThis;
            from[i * w] = 1;
            for (int j = 1; j <= m; j++) {
                int best = cost[(i - 1) * w + j - 1] + matchCost(kind[i - 1], isResent[j - 1], leftCost[j - 1]);
                byte how = 0;
                int skipEntry = cost[(i - 1) * w + j] + skipThis;
                if (skipEntry < best) {
                    best = skipEntry;
                    how = 1;
                }
                int skipHeld = cost[i * w + j - 1] + SKIP;
                if (skipHeld < best) {
                    best = skipHeld;
                    how = 2;
                }
                cost[i * w + j] = best;
                from[i * w + j] = how;
            }
        }
        int[] out = new int[count];
        int i = count;
        int j = m;
        while (i > 0 || j > 0) {
            byte how = from[i * w + j];
            if (how == 0) {
                out[--i] = --j;
            } else if (how == 1) {
                out[--i] = -1;
            } else {
                j--;
            }
        }
        return out;
    }

    private static boolean consistent(byte[] kind, boolean[] isResent) {
        for (int i = 0; i < isResent.length; i++) {
            if (isResent[i] && kind[i] != REMOVE) {
                return false;
            }
        }
        return true;
    }

    private static int matchCost(byte kind, boolean resent, int left) {
        if (kind == REMOVE) {
            return resent ? 0 : left;
        }
        return resent ? CONTRADICTION : 0;
    }
}
