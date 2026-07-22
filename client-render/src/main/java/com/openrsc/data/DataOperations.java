package com.openrsc.data;

import java.util.Arrays;

public class DataOperations {
	private static final char[] special_characters = "~`!@#$%^&*()_-+={}[]|'\";:?><,./".toCharArray();

	static {
		Arrays.sort(special_characters);
	}

	public static int getUnsigned2Bytes(byte abyte0[], int i) {
		return ((abyte0[i] & 0xff) << 8) + (abyte0[i + 1] & 0xff);
	}

	public static int getDataFileOffset(String filename, byte data[]) {
		int numEntries = getUnsigned2Bytes(data, 0);
		int wantedHash = 0;
		filename = filename.toUpperCase();
		for (int k = 0; k < filename.length(); k++)
			wantedHash = (wantedHash * 61 + filename.charAt(k)) - 32;

		int offset = 2 + numEntries * 10;
		for (int entry = 0; entry < numEntries; entry++) {
			int fileHash = (data[entry * 10 + 2] & 0xff) * 0x1000000 + (data[entry * 10 + 3] & 0xff) * 0x10000
				+ (data[entry * 10 + 4] & 0xff) * 256 + (data[entry * 10 + 5] & 0xff);
			int fileSize = (data[entry * 10 + 9] & 0xff) * 0x10000 + (data[entry * 10 + 10] & 0xff) * 256
				+ (data[entry * 10 + 11] & 0xff);
			if (fileHash == wantedHash)
				return offset;
			offset += fileSize;
		}

		return 0;
	}

	public static byte[] loadData(String file, int len, byte[] arc) {
		return loadData(file, len, arc, null);
	}

	public static byte[] loadData(String file, int len, byte[] arc, byte[] dest) {
		int arc_length = (arc[0] & 0xff) * 256 + (arc[1] & 0xff);
		int hash = 0;
		file = file.toUpperCase();
		for (int i = 0; i < file.length(); i++)
			hash = hash * 61 + file.charAt(i) - 32;
		int offset = 2 + arc_length * 10;
		for (int i = 0; i < arc_length; i++) {
			int entry_hash = (arc[(i * 10 + 2)] & 0xFF) * 16777216 + (arc[(i * 10 + 3)] & 0xFF) * 65536
				+ (arc[(i * 10 + 4)] & 0xFF) * 256 + (arc[(i * 10 + 5)] & 0xFF);
			int decmp_len = (arc[(i * 10 + 6)] & 0xFF) * 65536 + (arc[(i * 10 + 7)] & 0xFF) * 256
				+ (arc[(i * 10 + 8)] & 0xFF);
			int cmp_len = (arc[(i * 10 + 9)] & 0xFF) * 65536 + (arc[(i * 10 + 10)] & 0xFF) * 256
				+ (arc[(i * 10 + 11)] & 0xFF);

			if (entry_hash == hash) {
				if (dest == null)
					dest = new byte[decmp_len + len];
				if (decmp_len != cmp_len)
					DataFileDecrypter.unpackData(dest, decmp_len, arc, cmp_len, offset);
				else {
					for (int ii = 0; ii < decmp_len; ii++) {
						dest[ii] = arc[(offset + ii)];
					}
				}
				return dest;
			}
			offset += cmp_len;
		}
		return null;
	}

}
