package openrsc.bot.api;

import org.jetbrains.annotations.Nullable;

public record ServerMessage(
        long icon,
        int type,
        String text,
        @Nullable String sender,
        @Nullable String colour) {

    public static final int TYPE_TRADE = 6;

    public static ServerMessage plain(String text) {
        return new ServerMessage(0, 0, text, null, null);
    }

    public @Nullable String displayText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (type == TYPE_TRADE && sender != null && !sender.isEmpty()) {
            return sender + ": wishes to trade with you";
        }
        return null;
    }
}
