package openrsc.bot.api;

final class Ids {

    private Ids() {}

    static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }
}
