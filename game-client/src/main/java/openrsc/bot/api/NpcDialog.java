package openrsc.bot.api;

public record NpcDialog(int npcServerIndex, int tick, int recipientIndex, boolean directedAtUs, String message) {}
