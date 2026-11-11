package openrsc.bot.core.login;

public final class Passwords {

    public static final int LENGTH = 20;

    private Passwords() {}

    public static String pad(String pw) {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            if (i >= pw.length()) {
                out.append(' ');
            } else {
                char c = pw.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                    out.append(c);
                } else {
                    out.append('_');
                }
            }
        }
        return out.toString();
    }
}
