package com.openrsc.data;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class DataConversions {


	/**
	 * Returns a ByteBuffer containing everything available from the given
	 * InputStream
	 */
	public static ByteBuffer streamToBuffer(BufferedInputStream in)
		throws IOException {
		byte[] buffer = new byte[in.available()];
		in.read(buffer, 0, buffer.length);
		return ByteBuffer.wrap(buffer);
	}

}
