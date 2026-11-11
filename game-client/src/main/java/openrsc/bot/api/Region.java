package openrsc.bot.api;

public record Region(int worldX, int worldZ, int planeIndex, int planeMultiplier, int regionX, int regionZ) {

    public static final Region EMPTY = new Region(0, 0, 0, 0, 0, 0);
}
