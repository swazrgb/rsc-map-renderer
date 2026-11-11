package openrsc.bot.core.codec;

import java.util.HashMap;

public final class Huffman {

    private static final char[] SPECIAL_CHARS = {
        '€', '?', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '?', 'Ž', '?', '?', '‘', '’', '“', '”', '•',
        '–', '—', '˜', '™', 'š', '›', 'œ', '?', 'ž', 'Ÿ'
    };

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

    private static final int[] CIPHER_BLOCK = new int[INIT.length];
    private static int[] cipherDictionary = new int[8];
    private static final HashMap<Character, Character> SPECIAL_CHAR_MAP = new HashMap<>();

    static {
        for (int i = 0; i < SPECIAL_CHARS.length; i++) {
            SPECIAL_CHAR_MAP.put(SPECIAL_CHARS[i], (char) (i - 128));
        }

        final int[] blockBuilder = new int[33];
        int cipherDictIndexTemp = 0;
        for (int initPos = 0; initPos < INIT.length; ++initPos) {
            final int initValue = INIT[initPos];
            final int builderBitSelector = 1 << (32 - initValue);
            final int builderValue = blockBuilder[initValue];
            CIPHER_BLOCK[initPos] = builderValue;
            int builderValueBit;
            if ((builderValue & builderBitSelector) == 0) {
                builderValueBit = builderValue | builderBitSelector;
                for (int j = initValue - 1; j > 0; --j) {
                    final int builderValue2 = blockBuilder[j];
                    if (builderValue != builderValue2) {
                        break;
                    }
                    final int builderValue2BitSelector = 1 << (32 - j);
                    if ((builderValue2 & builderValue2BitSelector) == 0) {
                        blockBuilder[j] = builderValue2BitSelector | builderValue2;
                    } else {
                        blockBuilder[j] = blockBuilder[j - 1];
                        break;
                    }
                }
            } else {
                builderValueBit = blockBuilder[initValue - 1];
            }
            blockBuilder[initValue] = builderValueBit;
            for (int j = initValue + 1; j <= 32; ++j) {
                if (builderValue == blockBuilder[j]) {
                    blockBuilder[j] = builderValueBit;
                }
            }
            int cipherDictIndex = 0;
            for (int j = 0; j < initValue; ++j) {
                int builderBitSelector2 = 0x80000000 >>> j;
                if ((builderValue & builderBitSelector2) == 0) {
                    cipherDictIndex++;
                } else {
                    if (cipherDictionary[cipherDictIndex] == 0) {
                        cipherDictionary[cipherDictIndex] = cipherDictIndexTemp;
                    }
                    cipherDictIndex = cipherDictionary[cipherDictIndex];
                }
                if (cipherDictionary.length <= cipherDictIndex) {
                    final int[] grown = new int[cipherDictionary.length * 2];
                    System.arraycopy(cipherDictionary, 0, grown, 0, cipherDictionary.length);
                    cipherDictionary = grown;
                }
            }
            cipherDictionary[cipherDictIndex] = ~initPos;
            if (cipherDictIndex >= cipherDictIndexTemp) {
                cipherDictIndexTemp = cipherDictIndex + 1;
            }
        }
    }

    private Huffman() {}

    public static byte[] encode(String message) {
        char[] charBuffer = new char[message.length()];
        for (int i = 0; i < message.length(); i++) {
            final char c = message.charAt(i);
            if ((c <= '\0' || c >= '') && (c < ' ' || c > 'ÿ')) {
                charBuffer[i] = SPECIAL_CHAR_MAP.getOrDefault(c, '?');
            } else {
                charBuffer[i] = c;
            }
        }

        byte[] out = new byte[message.length() * 4 + 1];
        int encipheredByte = 0;
        int outputBitOffset = 0;
        for (int i = 0; i < message.length(); ++i) {
            final int sym = charBuffer[i] & 0xff;
            final int code = CIPHER_BLOCK[sym];
            final int codeLen = INIT[sym];

            int outputByteOffset = outputBitOffset >> 3;
            int shifter = 0x7 & outputBitOffset;
            encipheredByte &= -shifter >> 31;
            final int outputByteOffset2 = outputByteOffset + ((shifter + codeLen - 1) >> 3);
            outputBitOffset += codeLen;
            shifter += 24;
            encipheredByte |= (code >>> shifter);
            out[outputByteOffset] = (byte) encipheredByte;
            if (outputByteOffset2 > outputByteOffset) {
                outputByteOffset++;
                shifter -= 8;
                encipheredByte = code >>> shifter;
                out[outputByteOffset] = (byte) encipheredByte;
                if (outputByteOffset < outputByteOffset2) {
                    outputByteOffset++;
                    shifter -= 8;
                    encipheredByte = code >>> shifter;
                    out[outputByteOffset] = (byte) encipheredByte;
                    if (outputByteOffset2 > outputByteOffset) {
                        outputByteOffset++;
                        shifter -= 8;
                        encipheredByte = code >>> shifter;
                        out[outputByteOffset] = (byte) encipheredByte;
                        if (outputByteOffset2 > outputByteOffset) {
                            shifter -= 8;
                            outputByteOffset++;
                            encipheredByte = code << -shifter;
                            out[outputByteOffset] = (byte) encipheredByte;
                        }
                    }
                }
            }
        }

        int encipheredLength = (outputBitOffset + 7) >> 3;
        byte[] trimmed = new byte[encipheredLength];
        System.arraycopy(out, 0, trimmed, 0, encipheredLength);
        return trimmed;
    }

    public static String decode(byte[] src, int srcOffset, int decodedCharCount) {
        if (decodedCharCount == 0) {
            return "";
        }

        byte[] dest = new byte[decodedCharCount];
        int destOffset = 0;
        final int destEnd = decodedCharCount;

        int node = 0;
        int srcIdx = srcOffset;
        while (true) {
            final byte b = src[srcIdx];

            if (b >= 0) {
                ++node;
            } else {
                node = cipherDictionary[node];
            }
            int v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x40) != 0) {
                node = cipherDictionary[node];
            } else {
                ++node;
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x20) == 0) {
                ++node;
            } else {
                node = cipherDictionary[node];
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x10) != 0) {
                node = cipherDictionary[node];
            } else {
                ++node;
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x08) != 0) {
                node = cipherDictionary[node];
            } else {
                ++node;
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x04) != 0) {
                node = cipherDictionary[node];
            } else {
                ++node;
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x02) == 0) {
                ++node;
            } else {
                node = cipherDictionary[node];
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            if ((b & 0x01) != 0) {
                node = cipherDictionary[node];
            } else {
                ++node;
            }
            v = cipherDictionary[node];
            if (v < 0) {
                dest[destOffset++] = (byte) ~v;
                if (destOffset >= destEnd) {
                    break;
                }
                node = 0;
            }

            ++srcIdx;
        }

        StringBuilder sb = new StringBuilder(decodedCharCount);
        for (int i = 0; i < decodedCharCount; i++) {
            sb.append((char) (dest[i] & 0xff));
        }
        return sb.toString();
    }
}
