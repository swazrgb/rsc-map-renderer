package openrsc.bot.core.login;

public final class Usernames {

    private Usernames() {}

    public static String canonicalize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }
}
