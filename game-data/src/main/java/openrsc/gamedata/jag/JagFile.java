package openrsc.gamedata.jag;

/**
 * A read cursor over one unpacked {@link JagArchive} entry, starting at offset 0. Reading past the
 * end throws {@link ArrayIndexOutOfBoundsException}. Not thread-safe.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/io/JContentFile.java#L10">JContentFile</a>
 */
public final class JagFile {

    private final byte[] data;
    private int pos;

    /** Reads {@code data} in place; the array is not copied. */
    public JagFile(byte[] data) {
        this.data = data;
    }

    public byte readByte() {
        return data[pos++];
    }

    public int readUnsignedByte() {
        return data[pos++] & 0xFF;
    }
}
