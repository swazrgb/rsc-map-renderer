package orsc.util;

import java.io.*;

public class GenUtil {

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

    public static RSRuntimeError makeThrowable(Throwable error, String msg) {
        RSRuntimeError var2;
        if (error instanceof RSRuntimeError rsRuntimeError) {
            var2 = rsRuntimeError;
            var2.message = var2.message + ' ' + msg;
        } else {
            var2 = new RSRuntimeError(error, msg);
        }

        return var2;
    }
}
