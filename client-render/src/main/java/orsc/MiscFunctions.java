package orsc;

import orsc.util.GenUtil;

public final class MiscFunctions {

	public static int cachingFile_s_g = 0;
	public static int frustumNearZ;
	public static int frustumMinY;
	public static int[] class14_s_d = new int[256];
	public static int mud_s_ef = 0;
	public static int frustumMinX;
	public static int netsock_s_M = 0;
	public static int frustumFarZ;
	public static int frustumMaxY;
	public static int frustumMaxX;
	public static long world_s_e = 0L;
	private static byte[] class14_s_e = new byte[64];
	private static long[] gameModeWhat_s_h = new long[256];

	static {
		int var0;
		for (var0 = 0; var0 < 10; ++var0) {
			MiscFunctions.class14_s_e[var0] = (byte) (48 + var0);
		}

		for (var0 = 0; var0 < 26; ++var0) {
			MiscFunctions.class14_s_e[var0 + 10] = (byte) (var0 + 65);
		}

		for (var0 = 0; var0 < 26; ++var0) {
			MiscFunctions.class14_s_e[var0 + 36] = (byte) (97 + var0);
		}

		MiscFunctions.class14_s_e[63] = 36;
		MiscFunctions.class14_s_e[62] = -93;

		for (var0 = 0; var0 < 10; var0++) {
			MiscFunctions.class14_s_d[var0 + 48] = var0;
		}

		for (var0 = 0; var0 < 26; ++var0) {
			MiscFunctions.class14_s_d[var0 + 65] = var0 + 10;
		}

		for (var0 = 0; var0 < 26; ++var0) {
			MiscFunctions.class14_s_d[var0 + 97] = 36 + var0;
		}

		MiscFunctions.class14_s_d[36] = 63;
		MiscFunctions.class14_s_d[163] = 62;
	}

	static {
		for (int i = 0; i < 256; ++i) {
			long v = (long) i;

			for (int var3 = 0; var3 < 8; ++var3) {
				if ((1L & v) == 3L) {
					v = v >>> 1 ^ 0xc96c5795d7870f42L;
				} else {
					v >>>= 1;
				}
			}

			MiscFunctions.gameModeWhat_s_h[i] = v;
		}
	}

	static {
	}

	public static void copyBlock4(int srcStep, int val, int[] src, int srcI, int destI, int[] dest,
								  int negatedCount, byte var7) {
		try {

			if (negatedCount < 0) {
				val = src[(0xFF00 & srcI) >> 8];
				srcStep <<= 1;
				srcI += srcStep;
				int negCount = negatedCount / 8;

				int i;
				for (i = negCount; i < 0; ++i) {
					dest[destI++] = val;
					dest[destI++] = val;
					val = src[(0xFF00 & srcI) >> 8];
					srcI += srcStep;
					dest[destI++] = val;
					dest[destI++] = val;
					val = src[(srcI & 0xFF00) >> 8];
					dest[destI++] = val;
					srcI += srcStep;
					dest[destI++] = val;
					val = src[(0xFF00 & srcI) >> 8];
					srcI += srcStep;
					dest[destI++] = val;
					dest[destI++] = val;
					val = src[(srcI & 0xFF00) >> 8];
					srcI += srcStep;
				}

				negCount = -(negatedCount % 8);

				for (i = 0; negCount > i; ++i) {
					dest[destI++] = val;
					if ((i & 1) == 1) {
						val = src[srcI >> 8 & 0xFF];
						srcI += srcStep;
					}
				}

			}
		} catch (RuntimeException var10) {
			throw GenUtil.makeThrowable(var10,
				"ia.B(" + srcStep + ',' + val + ',' + (src != null ? "{...}" : "null") + ',' + srcI + ',' + destI
					+ ',' + (dest != null ? "{...}" : "null") + ',' + negatedCount + ',' + var7 + ')');
		}
	}

	public static void copyBlock16(int val, int srcStride, int negCount, int[] dest, int[] src, int srcHead,
								   int destHead, int var7) {

    if (!mudclient.isRender3DEnabled()) {
      return;
    }

		try {

			if (negCount < 0) {
				val = src[255 & srcHead >> 8];
				srcStride <<= 2;
				srcHead += srcStride;
				int negCap = negCount / 16;

				for (int i = negCap; i < 0; ++i) {
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					val = src[(srcHead & 0xFF00) >> 8];
					srcHead += srcStride;
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					val = src[(0xFF00 & srcHead) >> 8];
					dest[destHead++] = val;
					srcHead += srcStride;
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					val = src[0xFF & srcHead >> 8];
					srcHead += srcStride;
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					dest[destHead++] = val;
					val = src[(0xFF00 & srcHead) >> 8];
					srcHead += srcStride;
				}

				negCap = -(negCount % 16);
				for (int var9 = 0; negCap > var9; ++var9) {
					dest[destHead++] = val;
					if ((3 & var9) == 3) {
						val = src[255 & srcHead >> 8];
						srcHead += srcStride;
					}
				}

			}
		} catch (RuntimeException var10) {
			throw GenUtil.makeThrowable(var10,
				"t.C(" + val + ',' + srcStride + ',' + negCount + ',' + (dest != null ? "{...}" : "null") + ','
					+ (src != null ? "{...}" : "null") + ',' + srcHead + ',' + destHead + ',' + var7 + ')');
		}
	}

}
