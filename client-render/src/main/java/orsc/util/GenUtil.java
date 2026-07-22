package orsc.util;

import java.io.*;
import java.net.URL;

public class GenUtil {
	private static URL streamChooserContext = null;
	private static long lastTimeCall;
	private static long timeOverflow;

	public static InputStream chooseStreamFor(String file) throws IOException {
		try {
			InputStream stream;
			if (null != GenUtil.streamChooserContext) {
				URL var3 = new URL(GenUtil.streamChooserContext, file);
				stream = var3.openStream();
			} else {
				stream = new BufferedInputStream(new FileInputStream(file));
			}
			return stream;
		} catch (RuntimeException var4) {
			throw makeThrowable(var4, "nb.F(" + true + ',' + (file != null ? "{...}" : "null") + ')');
		}
	}

	public static int colorToResource(int r, int g, int b) {
		try {
			b >>= 3;
			r >>= 3;
			g >>= 3;
			return -(g << 5) - 1 - (r << 10) - b;
		} catch (RuntimeException var5) {
			throw makeThrowable(var5, "da.C(" + b + ',' + -66 + ',' + r + ',' + g + ')');
		}
	}

	public static synchronized long currentTimeMillis() {
		try {
			long time = System.currentTimeMillis();
			if (GenUtil.lastTimeCall > time) {
				GenUtil.timeOverflow += GenUtil.lastTimeCall - time;
			}

			GenUtil.lastTimeCall = time;
			return GenUtil.timeOverflow + time;
		} catch (RuntimeException var3) {
			throw makeThrowable(var3, "p.A(" + 0 + ')');
		}
	}


	public static RSRuntimeError makeThrowable(Throwable error, String msg) {
    RSRuntimeError var2;
    if (error instanceof RSRuntimeError) {
      var2 = (RSRuntimeError) error;
      var2.message = var2.message + ' ' + msg;
    } else {
      var2 = new RSRuntimeError(error, msg);
    }

    return var2;
  }

}
