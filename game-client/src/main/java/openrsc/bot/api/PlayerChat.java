package openrsc.bot.api;

public record PlayerChat(
        int senderIndex, String sender, String text, int icon, int type, boolean muted, boolean tutorial) {

    public static final int TYPE_SPOKEN = 1;

    public boolean spoken() {
        return type == TYPE_SPOKEN;
    }

    public PlayerChat named(String name) {
        return new PlayerChat(senderIndex, name, text, icon, type, muted, tutorial);
    }
}
