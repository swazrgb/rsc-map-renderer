package openrsc.bot.api;

final class Tiles {

    private Tiles() {}

    static long tile(int x, int z) {
        return ((long) (x & 0xFFFF) << 16) | (z & 0xFFFF);
    }
}
