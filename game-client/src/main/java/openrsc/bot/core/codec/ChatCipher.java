package openrsc.bot.core.codec;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class ChatCipher {

    private ChatCipher() {}

    private static final byte[] INIT = {
        22, 22, 22, 22, 22, 22, 21, 22, 22, 20, 22, 22, 22, 21, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 3, 8, 22, 16, 22, 16, 17, 7, 13, 13, 13, 16, 7, 10, 6, 16, 10, 11, 12, 12, 12, 12, 13, 13, 14,
        14, 11, 14, 19, 15, 17, 8, 11, 9, 10, 10, 10, 10, 11, 10, 9, 7, 12, 11, 10, 10, 9, 10, 10, 12, 10, 9, 8, 12, 12,
        9, 14, 8, 12, 17, 16, 17, 22, 13, 21, 4, 7, 6, 5, 3, 6, 6, 5, 4, 10, 7, 5, 6, 4, 4, 6, 10, 5, 4, 4, 5, 7, 6, 10,
        6, 10, 22, 19, 22, 14, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22,
        22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 22, 21, 22, 21, 22, 22, 22, 21, 22, 22
    };

    private static final char[] SPECIAL_CHARACTERS = {
        '€', '?', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '?', 'Ž', '?', '?', '‘', '’', '“', '”', '•',
        '–', '—', '˜', '™', 'š', '›', 'œ', '?', 'ž', 'Ÿ'
    };

    private static final Map<Character, Character> SPECIAL_CHARACTER_MAP = new HashMap<>();

    private static final int[] CIPHER_BLOCK = new int[INIT.length];

    static {
        for (int i = 0; i < SPECIAL_CHARACTERS.length; ++i) {
            SPECIAL_CHARACTER_MAP.put(SPECIAL_CHARACTERS[i], (char) (i - 128));
        }
        final int[] blockBuilder = new int[33];
        for (int initPos = 0; initPos < INIT.length; ++initPos) {
            final int initValue = INIT[initPos];
            final int builderBitSelector = 1 << (32 - initValue);
            final int builderValue = blockBuilder[initValue];
            CIPHER_BLOCK[initPos] = builderValue;
            int builderValueBit;
            if ((builderValue & builderBitSelector) == 0) {
                builderValueBit = builderValue | builderBitSelector;
                for (int c = initValue - 1; c > 0; --c) {
                    final int builderValue2 = blockBuilder[c];
                    if (builderValue != builderValue2) {
                        break;
                    }
                    final int builderValue2BitSelector = 1 << (32 - c);
                    if ((builderValue2 & builderValue2BitSelector) == 0) {
                        blockBuilder[c] = builderValue2BitSelector | builderValue2;
                    } else {
                        blockBuilder[c] = blockBuilder[c - 1];
                        break;
                    }
                }
            } else {
                builderValueBit = blockBuilder[initValue - 1];
            }
            blockBuilder[initValue] = builderValueBit;
            for (int c = initValue + 1; c <= 32; ++c) {
                if (builderValue == blockBuilder[c]) {
                    blockBuilder[c] = builderValueBit;
                }
            }
        }
    }

    public static byte[] encipher(String message) {
        final int len = message.length();
        final char[] chars = convertMessageToBytes(message);
        final byte[] out = new byte[len * 3 + 8];
        int encipheredByte = 0;
        int outputBitOffset = 0;
        for (int i = 0; i < len; ++i) {
            final int messageCharacter = chars[i] & 0xff;
            final int cipherBlockValue = CIPHER_BLOCK[messageCharacter];
            final int initValue = INIT[messageCharacter];

            int outputByteOffset = outputBitOffset >> 3;
            int cipherBlockShifter = 0x7 & outputBitOffset;
            encipheredByte &= -cipherBlockShifter >> 31;
            final int outputByteOffset2 = outputByteOffset + ((cipherBlockShifter + initValue - 1) >> 3);
            outputBitOffset += initValue;
            cipherBlockShifter += 24;
            encipheredByte |= (cipherBlockValue >>> cipherBlockShifter);
            out[outputByteOffset] = (byte) encipheredByte;
            if (outputByteOffset2 > outputByteOffset) {
                outputByteOffset++;
                cipherBlockShifter -= 8;
                encipheredByte = cipherBlockValue >>> cipherBlockShifter;
                out[outputByteOffset] = (byte) encipheredByte;
                if (outputByteOffset < outputByteOffset2) {
                    outputByteOffset++;
                    cipherBlockShifter -= 8;
                    encipheredByte = cipherBlockValue >>> cipherBlockShifter;
                    out[outputByteOffset] = (byte) encipheredByte;
                    if (outputByteOffset2 > outputByteOffset) {
                        outputByteOffset++;
                        cipherBlockShifter -= 8;
                        encipheredByte = cipherBlockValue >>> cipherBlockShifter;
                        out[outputByteOffset] = (byte) encipheredByte;
                        if (outputByteOffset2 > outputByteOffset) {
                            cipherBlockShifter -= 8;
                            outputByteOffset++;
                            encipheredByte = cipherBlockValue << -cipherBlockShifter;
                            out[outputByteOffset] = (byte) encipheredByte;
                        }
                    }
                }
            }
        }
        final int encipheredLength = (outputBitOffset + 7) >> 3;
        return Arrays.copyOf(out, encipheredLength);
    }

    private static char[] convertMessageToBytes(CharSequence cs) {
        final char[] buf = new char[cs.length()];
        for (int i = 0; i < cs.length(); ++i) {
            final char c = cs.charAt(i);
            if ((('\0' >= c) || ('' <= c)) && ((' ' > c) || ('ÿ' < c))) {
                buf[i] = SPECIAL_CHARACTER_MAP.getOrDefault(c, '?');
            } else {
                buf[i] = c;
            }
        }
        return buf;
    }
}
