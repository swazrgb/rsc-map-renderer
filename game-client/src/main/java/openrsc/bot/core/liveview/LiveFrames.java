package openrsc.bot.core.liveview;

public final class LiveFrames {

    private LiveFrames() {}

    public static byte[] serverFrame(byte[] body) {
        return prefix(body, body.length + 2);
    }

    public static byte[] clientFrame(byte[] body) {
        return prefix(body, body.length);
    }

    private static byte[] prefix(byte[] body, int lengthField) {
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) (lengthField >> 8);
        out[1] = (byte) lengthField;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }
}
