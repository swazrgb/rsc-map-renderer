package openrsc.sessionproxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/** A client's connection as a session writes to it: the stream its bytes go on, and closing it. */
public interface ClientLink extends Closeable {

    /** The stream the client's bytes are written to: the same one on every call, written by one thread at a time. */
    OutputStream output() throws IOException;

    static ClientLink of(Socket socket) {
        return new ClientLink() {
            @Override
            public OutputStream output() throws IOException {
                return socket.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
    }
}
