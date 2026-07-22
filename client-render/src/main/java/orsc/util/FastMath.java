package orsc.util;

public class FastMath {

	public static int[] trigTable256 = new int[512];
	public static int[] trigTable1024 = new int[2048];
	private static int[] trigTable_256 = new int[512];
	public static int[] trigTable_1024 = new int[2048];

	static {
		for (int i = 0; i < 256; ++i) {
			FastMath.trigTable256[i] = (int) (32768.0D * Math.sin(0.02454369D * (double) i));
			FastMath.trigTable256[256 + i] = (int) (32768.0D * Math.cos((double) i * 0.02454369D));
		}

		for (int i = 0; i < 1024; ++i) {
			FastMath.trigTable1024[i] = (int) (Math.sin((double) i * 0.00613592315D) * 32768.0D);
			FastMath.trigTable1024[i + 1024] = (int) (Math.cos((double) i * 0.00613592315D) * 32768.0D);
		}

		for (int i = 0; i < 256; ++i) {
			FastMath.trigTable_256[i] = (int) (Math.sin(0.02454369D * (double) i) * 32768.0D);
			FastMath.trigTable_256[256 + i] = (int) (Math.cos((double) i * 0.02454369D) * 32768.0D);
		}

		for (int i = 0; i < 1024; ++i) {
			FastMath.trigTable_1024[i] = (int) (Math.sin((double) i * 0.00613592315D) * 32768.0D);
			FastMath.trigTable_1024[1024 + i] = (int) (Math.cos((double) i * 0.00613592315D) * 32768.0D);
		}
	}

	public static int bitwiseAnd(int var0, int var1) {
		try {
			return var0 & var1;
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "ib.QA(" + var0 + ',' + var1 + ')');
		}
	}

	public static int bitwiseOr(int var0, int var1) {
		try {
			return var0 | var1;
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "d.B(" + var0 + ',' + var1 + ')');
		}
	}

	public static int byteToUByte(byte val) {
		try {
			return val & 255;
		} catch (RuntimeException var3) {
			throw GenUtil.makeThrowable(var3, "nb.G(" + "dummy" + ',' + val + ')');
		}
	}

}
