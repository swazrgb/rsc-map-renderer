package openrsc.sessionproxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import openrsc.bot.core.world.Opcode;

final class HeldMessages {

    static final int CAPACITY = 256;

    private static final int SERVER_MESSAGE_OPCODE = Opcode.SEND_SERVER_MESSAGE.wire();

    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();

    void capture(int opcode, byte[] body) {
        if (opcode != SERVER_MESSAGE_OPCODE) {
            return;
        }
        if (frames.size() >= CAPACITY) {
            frames.pollFirst();
        }
        frames.addLast(body);
    }

    List<byte[]> drain() {
        List<byte[]> out = new ArrayList<>(frames);
        frames.clear();
        return out;
    }

    int size() {
        return frames.size();
    }
}
