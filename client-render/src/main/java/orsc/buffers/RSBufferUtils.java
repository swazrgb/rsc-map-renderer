package orsc.buffers;

import orsc.util.FastMath;
import orsc.util.GenUtil;

public class RSBufferUtils {

	public static int get16(int offset, byte[] data) {
		try {

			return (data[1 + offset] & 255) + ((255 & data[offset]) << 8);
		} catch (RuntimeException var4) {
			throw GenUtil.makeThrowable(var4,
				"d.D(" + offset + ',' + "dummy" + ',' + (data != null ? "{...}" : "null") + ')');
		}
	}

	public static int readShort(byte[] data, int var1, int index) {
		try {

			int val = FastMath.byteToUByte(data[index]) * 256 + FastMath.byteToUByte(data[1 + index]);
			if (val > 32767) {
				val -= 65536;
			}

			return val;
		} catch (RuntimeException var4) {
			throw GenUtil.makeThrowable(var4,
				"w.B(" + "{...}" + ',' + -1 + ',' + index + ')');
		}
	}

}
