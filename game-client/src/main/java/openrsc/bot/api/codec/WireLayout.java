package openrsc.bot.api.codec;

public record WireLayout(boolean bankNotes, boolean dropX) {

    public static final WireLayout AUTHENTIC = new WireLayout(false, false);
}
