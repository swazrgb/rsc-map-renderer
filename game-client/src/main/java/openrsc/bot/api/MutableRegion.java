package openrsc.bot.api;

public final class MutableRegion {
    private int worldX;
    private int worldZ;
    private int planeIndex;
    private int planeMultiplier;
    private int regionX;
    private int regionZ;
    private Region snap = Region.EMPTY;

    public void loadArea(int worldX, int worldZ, int planeIndex, int planeMultiplier) {
        snap = null;
        this.worldX = worldX;
        this.worldZ = worldZ;
        this.planeIndex = planeIndex;
        this.planeMultiplier = planeMultiplier;
    }

    public void coords(int rawX, int rawZ) {
        int wantX = rawX + worldX;
        int wantZ = rawZ + worldZ;
        int regX = (wantX + 24) / 48;
        int regZ = (wantZ + 24) / 48;
        int nx = (regX * 48 - 48) - worldX;
        int nz = (regZ * 48 - 48) - worldZ;
        if (nx != regionX || nz != regionZ) {
            snap = null;
            regionX = nx;
            regionZ = nz;
        }
    }

    public int worldX() {
        return worldX;
    }

    public int worldZ() {
        return worldZ;
    }

    public Region snapshot() {
        Region cached = snap;
        if (cached == null) {
            cached = new Region(worldX, worldZ, planeIndex, planeMultiplier, regionX, regionZ);
            snap = cached;
        }
        return cached;
    }
}
