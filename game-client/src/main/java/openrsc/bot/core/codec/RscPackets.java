package openrsc.bot.core.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import openrsc.bot.core.world.Opcode;

public final class RscPackets {

    public static final int NEWLINE = 0x0A;

    private RscPackets() {}

    public static ByteBuf create(Opcode opcode) {
        return create(opcode.wire());
    }

    public static ByteBuf create(int opcode) {
        ByteBuf b = Unpooled.buffer(64);
        b.writeShort(0);
        b.writeByte(opcode);
        return b;
    }

    public static ByteBuf finish(ByteBuf b) {
        int length = b.writerIndex() - 2;
        b.setShort(0, length);
        return b;
    }

    public static int opcodeOf(ByteBuf framed) {
        return framed.getUnsignedByte(2);
    }

    public static void writeSmart08_16(ByteBuf b, int v) {
        if (v >= 0 && v < 128) {
            b.writeByte(v);
        } else {
            b.writeShort(0x8000 + v);
        }
    }

    public static int readSmart08_16(ByteBuf b) {
        int first = b.getUnsignedByte(b.readerIndex());
        if (first < 128) {
            return b.readUnsignedByte();
        }
        return b.readUnsignedShort() - 0x8000;
    }

    public static void writeNlString(ByteBuf b, String s) {
        b.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        b.writeByte(NEWLINE);
    }

    public static String readNlString(ByteBuf b) {
        int start = b.readerIndex();
        while (b.isReadable()) {
            if (b.readByte() == NEWLINE) {
                int end = b.readerIndex() - 1;
                return b.toString(start, end - start, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("readNlString: no newline before end of buffer");
    }

    public static int readBits(ByteBuf b, int bitOffset, int length) {
        int byteOffset = bitOffset >> 3;
        int innerOffset = 8 - (bitOffset & 7);
        int bits = 0;

        for (; length > innerOffset; innerOffset = 8) {
            bits += (b.getUnsignedByte(byteOffset) & MASKS[innerOffset]) << (length - innerOffset);
            byteOffset++;
            length -= innerOffset;
        }

        if (length == innerOffset) {
            bits += b.getUnsignedByte(byteOffset) & MASKS[innerOffset];
        } else {
            bits += (b.getUnsignedByte(byteOffset) >> (innerOffset - length)) & MASKS[length];
        }
        return bits;
    }

    private static final int[] MASKS = {
        0,
        1,
        3,
        7,
        15,
        31,
        63,
        127,
        255,
        511,
        1023,
        2047,
        4095,
        8191,
        16383,
        32767,
        65535,
        0x1ffff,
        0x3ffff,
        0x7ffff,
        0xfffff,
        0x1fffff,
        0x3fffff,
        0x7fffff,
        0xffffff,
        0x1ffffff,
        0x3ffffff,
        0x7ffffff,
        0xfffffff,
        0x1fffffff,
        0x3fffffff,
        0x7fffffff,
        -1,
    };
}
