package openrsc.sessionproxy;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * One way a client can talk to the proxy. The proxy reads a connection's first bytes and hands it to the first
 * protocol that recognizes them; a connection no protocol recognizes is closed.
 */
public interface ClientProtocol {

    /** How many of a connection's first bytes {@link #recognizes} is shown, at most. */
    int HEAD_BYTES = 3;

    /** Whether {@code head}, the first bytes the client sent (fewer only if it stopped there), open this protocol. */
    boolean recognizes(byte[] head);

    /**
     * Serves the connection until the client leaves. {@code in} still holds the bytes {@link #recognizes} was shown.
     * Runs on a thread of its own; the proxy closes the socket after it returns.
     */
    void serve(Socket socket, DataInputStream in, SessionProxy proxy) throws IOException;
}
