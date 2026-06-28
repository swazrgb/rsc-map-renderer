package openrsc.bot.core.world.jag;

/**
 * Sequential byte reader over a decompressed {@link JagArchive} entry. Port of the server's
 * {@code com.openrsc.server.io.JContentFile} — same big-endian, sign conventions so the sector
 * decoder reproduces the server's bytes exactly.
 */
public final class JagFile {

  private final byte[] data;
  private int pos;

  public JagFile(byte[] data) {
    this.data = data;
  }

  public void skip(int amount) {
    pos += amount;
  }

  public int tell() {
    return pos;
  }

  public byte readByte() {
    return data[pos++];
  }

  public int readUnsignedByte() {
    return data[pos++] & 0xFF;
  }

  public int readUnsignedShort() {
    return (readUnsignedByte() << 8) | readUnsignedByte();
  }

  public int remaining() {
    return data.length - pos;
  }
}
