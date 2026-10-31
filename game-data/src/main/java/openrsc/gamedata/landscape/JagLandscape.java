package openrsc.gamedata.landscape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import openrsc.gamedata.jag.JagArchive;
import openrsc.gamedata.jag.JagFile;

/**
 * The JAG map archives of one map revision, decoded sector by sector as the server's
 * {@code WorldLoader.loadJAGSector} decodes them: a sector's {@code .jm}, {@code .dat},
 * {@code .hei} and {@code .loc} entries, with a members' {@code .mem} entry replacing the free
 * one when {@code memberWorld} is set.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L50">WorldLoader.loadJAGSector</a>
 */
public final class JagLandscape implements LandscapeSource {

    private static final int SIZE = RawSector.SIZE;

    private final Path mapsDir;
    private final int rev;
    private final JagArchive mapsJag;
    private final JagArchive mapsMem;
    private final JagArchive landJag;
    private final JagArchive landMem;
    private final boolean memberWorld;
    private final boolean altFormat;

    private JagLandscape(
            Path mapsDir,
            JagArchive mapsJag,
            JagArchive mapsMem,
            JagArchive landJag,
            JagArchive landMem,
            boolean memberWorld,
            int basedMapData) {
        this.mapsDir = mapsDir;
        this.rev = basedMapData;
        this.mapsJag = mapsJag;
        this.mapsMem = mapsMem;
        this.landJag = landJag;
        this.landMem = landMem;
        this.memberWorld = memberWorld;
        this.altFormat = basedMapData >= 28
                && basedMapData
                        <= 62; // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L343
    }

    /**
     * The archives of revision {@code rev} in {@code mapsDir}, or {@code null} when
     * {@code maps<rev>.jag} is absent or does not open; the {@code .mem} and {@code land} archives
     * are optional.
     *
     * @throws IOException when an archive exists but cannot be read
     */
    public static JagLandscape open(Path mapsDir, int rev, boolean memberWorld) throws IOException {
        boolean bzip2 = rev >= 28;
        Path mj = mapsDir.resolve("maps" + rev + ".jag");
        if (!Files.exists(mj)) {
            return null;
        }
        JagArchive mapsJag = JagArchive.open(mj, bzip2);
        if (mapsJag == null) {
            return null;
        }
        JagArchive mapsMem = JagArchive.open(mapsDir.resolve("maps" + rev + ".mem"), bzip2);
        JagArchive landJag = JagArchive.open(mapsDir.resolve("land" + rev + ".jag"), bzip2);
        JagArchive landMem = JagArchive.open(mapsDir.resolve("land" + rev + ".mem"), bzip2);
        return new JagLandscape(mapsDir, mapsJag, mapsMem, landJag, landMem, memberWorld, rev);
    }

    @Override
    public boolean exists(int height, int sectionX, int sectionY) {
        String name = sectorName(height, sectionX, sectionY);
        if (mapsJag.unpack(name + ".jm") != null || mapsJag.unpack(name + ".dat") != null) {
            return true;
        }
        return memberWorld
                && mapsMem != null
                && (mapsMem.unpack(name + ".jm") != null || mapsMem.unpack(name + ".dat") != null);
    }

    @Override
    public RawSector sector(int height, int sectionX, int sectionY) {
        String name = sectorName(height, sectionX, sectionY);

        JagFile jmFile = mapsJag.unpack(name + ".jm");
        JagFile datFile = mapsJag.unpack(name + ".dat");
        JagFile heiFile = landJag != null ? landJag.unpack(name + ".hei") : null;
        JagFile locFile = mapsJag.unpack(name + ".loc");

        if (memberWorld && mapsMem != null) {
            JagFile memberJM = mapsMem.unpack(name + ".jm");
            JagFile memberDat = mapsMem.unpack(name + ".dat");
            JagFile memberHei = landMem != null ? landMem.unpack(name + ".hei") : null;
            if (memberDat != null) {
                datFile = memberDat;
            }
            if (memberJM != null) {
                jmFile = memberJM;
            }
            if (memberHei != null) {
                heiFile = memberHei;
            }
        }

        if (jmFile == null && datFile == null) {
            return null;
        }

        byte[] terrainHeight = new byte[SIZE];
        byte[] terrainColour = new byte[SIZE];
        byte[] wallsEastWest = new byte[SIZE];
        byte[] wallsNorthSouth = new byte[SIZE];
        int[] wallsDiagonal = new int[SIZE];
        byte[] wallsRoof = new byte[SIZE];
        byte[] tileDecoration = new byte[SIZE];
        byte[] tileDirection = new byte[SIZE];
        int lastVal;

        if (datFile != null) {
            if (altFormat) {
                decodeDatAlt(
                        datFile,
                        wallsEastWest,
                        wallsNorthSouth,
                        wallsDiagonal,
                        wallsRoof,
                        tileDecoration,
                        tileDirection);
            } else {
                decodeDatClassic(
                        datFile,
                        wallsEastWest,
                        wallsNorthSouth,
                        wallsDiagonal,
                        wallsRoof,
                        tileDecoration,
                        tileDirection);
            }
        } else {
            // The server's defaults for a sector with no .dat entry.
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/WorldLoader.java#L212
            if (height == 0) {
                Arrays.fill(tileDecoration, (byte) -6);
            }
            if (height == 3) {
                Arrays.fill(tileDecoration, (byte) 8);
            }
            if (locFile != null) {
                for (int tile = 0; tile < SIZE; ) {
                    int val = locFile.readUnsignedByte();
                    if (val < 128) {
                        wallsDiagonal[tile++] = val + 48000;
                    } else {
                        tile += val - 128;
                    }
                }
            }
        }

        if (heiFile != null) {
            lastVal = 0;
            for (int tile = 0; tile < 2304; ) {
                int val = heiFile.readUnsignedByte();
                if (val < 128) {
                    terrainHeight[tile++] = (byte) val;
                    lastVal = val;
                } else {
                    for (int i = 0; i < val - 128; i++) {
                        terrainHeight[tile++] = (byte) lastVal;
                    }
                }
            }
            lastVal = 64;
            for (int tileY = 0; tileY < 48; tileY++) {
                for (int tileX = 0; tileX < 48; tileX++) {
                    lastVal = terrainHeight[tileX * 48 + tileY] + lastVal & 0x7f;
                    terrainHeight[tileX * 48 + tileY] = (byte) (lastVal * 2);
                }
            }
            lastVal = 0;
            for (int tile = 0; tile < 2304; ) {
                int val = heiFile.readUnsignedByte();
                if (val < 128) {
                    terrainColour[tile++] = (byte) val;
                    lastVal = val;
                } else {
                    for (int i = 0; i < val - 128; i++) {
                        terrainColour[tile++] = (byte) lastVal;
                    }
                }
            }
            lastVal = 35;
            for (int tileY = 0; tileY < 48; tileY++) {
                for (int tileX = 0; tileX < 48; tileX++) {
                    lastVal = terrainColour[tileX * 48 + tileY] + lastVal & 0x7f;
                    terrainColour[tileX * 48 + tileY] = (byte) (lastVal * 2);
                }
            }
        }

        if (jmFile != null) {
            int val = 0;
            for (int i = 0; i < SIZE; i++) {
                val += jmFile.readUnsignedByte();
                terrainHeight[i] = (byte) val;
            }
            val = 0;
            for (int i = 0; i < SIZE; i++) {
                val += jmFile.readUnsignedByte();
                terrainColour[i] = (byte) val;
            }
            for (int i = 0; i < SIZE; i++) {
                wallsEastWest[i] = jmFile.readByte();
            }
            for (int i = 0; i < SIZE; i++) {
                wallsNorthSouth[i] = jmFile.readByte();
            }
            for (int i = 0; i < SIZE; i++) {
                wallsDiagonal[i] = jmFile.readUnsignedByte() * 256 + jmFile.readUnsignedByte();
            }
            for (int i = 0; i < SIZE; i++) {
                wallsRoof[i] = jmFile.readByte();
            }
            for (int i = 0; i < SIZE; i++) {
                tileDecoration[i] = jmFile.readByte();
            }
            for (int i = 0; i < SIZE; i++) {
                tileDirection[i] = jmFile.readByte();
            }
        }

        RawSector s = new RawSector();
        System.arraycopy(terrainHeight, 0, s.groundElevation, 0, SIZE);
        System.arraycopy(terrainColour, 0, s.groundTexture, 0, SIZE);
        System.arraycopy(tileDecoration, 0, s.groundOverlay, 0, SIZE);
        System.arraycopy(wallsRoof, 0, s.roofTexture, 0, SIZE);
        System.arraycopy(wallsEastWest, 0, s.horizontalWall, 0, SIZE);
        System.arraycopy(wallsNorthSouth, 0, s.verticalWall, 0, SIZE);
        System.arraycopy(wallsDiagonal, 0, s.diagonalWalls, 0, SIZE);
        return s;
    }

    @Override
    public String describe() {
        return "JAG maps" + rev + (memberWorld && mapsMem != null ? "+.mem" : "") + " under " + mapsDir;
    }

    @Override
    public void close() {}

    private static String sectorName(int height, int sectionX, int sectionY) {
        return "m" + height + sectionX / 10 + sectionX % 10 + sectionY / 10 + sectionY % 10;
    }

    private static void decodeDatAlt(
            JagFile f, byte[] ew, byte[] ns, int[] diag, byte[] roof, byte[] deco, byte[] dir) {
        rleBytes(f, ew);
        rleBytes(f, ns);
        for (int i = 0; i < SIZE; ) {
            int val = f.readUnsignedByte();
            if (val < 128) {
                diag[i++] = val;
            } else {
                for (int x = 0; x < val - 128; x++) {
                    diag[i++] = 0;
                }
            }
        }
        for (int i = 0; i < SIZE; ) {
            int val = f.readUnsignedByte();
            if (val < 128) {
                diag[i++] = val + 12000;
            } else {
                i += val - 128;
            }
        }
        rleBytes(f, roof);
        rleBytes(f, deco);
        rleBytes(f, dir);
    }

    private static void decodeDatClassic(
            JagFile f, byte[] ew, byte[] ns, int[] diag, byte[] roof, byte[] deco, byte[] dir) {
        for (int i = 0; i < SIZE; i++) {
            ew[i] = f.readByte();
        }
        for (int i = 0; i < SIZE; i++) {
            ns[i] = f.readByte();
        }
        for (int i = 0; i < SIZE; i++) {
            diag[i] = f.readUnsignedByte();
        }
        for (int i = 0; i < SIZE; i++) {
            int val = f.readUnsignedByte();
            if (val > 0) {
                diag[i] = val + 12000;
            }
        }
        for (int tile = 0; tile < 2304; ) {
            int val = f.readUnsignedByte();
            if (val < 128) {
                roof[tile++] = (byte) val;
            } else {
                for (int i = 0; i < val - 128; i++) {
                    roof[tile++] = 0;
                }
            }
        }
        int lastVal = 0;
        for (int tile = 0; tile < 2304; ) {
            int val = f.readUnsignedByte();
            if (val < 128) {
                deco[tile++] = (byte) val;
                lastVal = val;
            } else {
                for (int i = 0; i < val - 128; i++) {
                    deco[tile++] = (byte) lastVal;
                }
            }
        }
        for (int tile = 0; tile < 2304; ) {
            int val = f.readUnsignedByte();
            if (val < 128) {
                dir[tile++] = (byte) val;
            } else {
                for (int i = 0; i < val - 128; i++) {
                    dir[tile++] = 0;
                }
            }
        }
    }

    private static void rleBytes(JagFile f, byte[] out) {
        for (int i = 0; i < SIZE; ) {
            int val = f.readUnsignedByte();
            if (val < 128) {
                out[i++] = (byte) val;
            } else {
                for (int x = 0; x < val - 128; x++) {
                    out[i++] = 0;
                }
            }
        }
    }
}
