package openrsc.bot.api;

public record PrivateMessage(String name, String formerName, long icon, String message, boolean sent) {}
