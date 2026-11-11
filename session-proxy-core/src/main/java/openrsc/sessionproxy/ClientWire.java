package openrsc.sessionproxy;

import java.io.DataInputStream;
import java.io.IOException;
import openrsc.bot.core.liveview.LiveFrames;
import org.jetbrains.annotations.Nullable;

/**
 * How one client's connection carries packets. A session speaks the custom client protocol with the server, so a wire
 * hands it what the client sends as custom-protocol packets, and puts the server's custom-protocol packets on the
 * client's connection in whatever form the client reads.
 *
 * <p>A packet here is a body: its opcode, then its payload. {@link #read} is called from the one thread reading the
 * client, and {@link #encode} from whichever thread queues the packet, one at a time and in the order the packets go
 * out.
 */
public interface ClientWire {

    /** The next packet the client sent, as the custom protocol carries it; null when the client has gone. */
    byte @Nullable [] read() throws IOException;

    /** The bytes that put {@code body}, a server packet of the custom protocol, on the client's connection. */
    byte[] encode(byte[] body);

    /** A client of the custom protocol, whose packets need no translating. */
    static ClientWire custom(DataInputStream in) {
        return new ClientWire() {
            @Override
            public byte @Nullable [] read() throws IOException {
                return Session.readFrame(in);
            }

            @Override
            public byte[] encode(byte[] body) {
                return LiveFrames.serverFrame(body);
            }
        };
    }
}
