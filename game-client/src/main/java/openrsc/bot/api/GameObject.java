package openrsc.bot.api;

import openrsc.gamedata.geometry.Targetable;

public record GameObject(int id, int x, int z, int dir) implements Targetable, Comparable<GameObject> {

    @Override
    public Targetable.Kind kind() {
        return Targetable.Kind.SCENERY;
    }

    @Override
    public int compareTo(GameObject o) {
        int result = Integer.compare(this.id, o.id);
        if (result != 0) {
            return result;
        }

        result = Integer.compare(this.x, o.x);
        if (result != 0) {
            return result;
        }

        result = Integer.compare(this.z, o.z);
        if (result != 0) {
            return result;
        }

        return Integer.compare(this.dir, o.dir);
    }
}
