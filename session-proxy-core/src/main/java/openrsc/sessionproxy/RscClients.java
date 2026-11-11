package openrsc.sessionproxy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import openrsc.bot.core.liveview.LiveFrames;
import openrsc.bot.core.login.PreLoginConfigsRequest;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OpenRSC custom client protocol, versions 10000 to 19999: the stock client, Plutonium and IdleRSC.
 *
 * <p>Before a login the proxy answers as the server does: the configs request with the server's own configs packet,
 * carrying the proxy's key when it writes its own logins ({@link SessionProxy#clientConfigs}), which a client encrypts
 * its password with. The first client to log in to an account opens its session with the login the proxy sends for it
 * ({@link SessionProxy#upstreamLogin}); a later one joins it when its password matches.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/RSCConnectionHandler.java#L98-L109">RSCConnectionHandler.channelRead</a>
 */
public final class RscClients implements ClientProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(RscClients.class);

    private static final int OPCODE_LOGIN = 0;
    private static final int MIN_LOGIN_PAYLOAD = 11;

    /** @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/LoginResponse.java#L10">LoginResponse.CLIENT_UPDATED</a> */
    private static final int CODE_CLIENT_UPDATED = 5;

    @Override
    public boolean recognizes(byte[] head) {
        if (head.length < 3) {
            return false;
        }
        int opcode = head[2] & 0xFF;
        return opcode == OPCODE_LOGIN || opcode == PreLoginConfigsRequest.OPCODE;
    }

    @Override
    public void serve(Socket socket, DataInputStream in, SessionProxy proxy) throws IOException {
        ClientLogin sent = open(in, socket.getOutputStream(), proxy);
        if (sent != null) {
            String label = sent.username() + " " + sent.describe() + " " + socket.getRemoteSocketAddress();
            ClientLogin login = upstream(sent, socket.getOutputStream(), proxy, label);
            if (login != null) {
                proxy.admit(login, socket, ClientWire.custom(in), label);
            }
        }
    }

    /**
     * Serves a client of the live map, read from {@code in} and written to {@code link}: the configs request as
     * {@link #serve} answers it, and a login that joins its account's held session ({@link SessionProxy#admitFromMap}).
     * The caller closes {@code link} after it returns.
     *
     * @param from where the client connected from, for the log
     */
    public static void serveFromMap(DataInputStream in, ClientLink link, SessionProxy proxy, String from)
            throws IOException {
        ClientLogin sent = open(in, link.output(), proxy);
        if (sent != null) {
            String label = sent.username() + " " + sent.describe() + " on the map " + from;
            ClientLogin login = upstream(sent, link.output(), proxy, label);
            if (login != null) {
                proxy.admitFromMap(login, link, ClientWire.custom(in), label);
            }
        }
    }

    /** The login the proxy sends for {@code sent}; null, the client sent the code to update and let go, for none. */
    private static @Nullable ClientLogin upstream(ClientLogin sent, OutputStream out, SessionProxy proxy, String label)
            throws IOException {
        ClientLogin login = proxy.upstreamLogin(sent);
        if (login == null) {
            LOG.info("[{}] refused: its password is not encrypted with the key the proxy hands out", label);
            out.write(CODE_CLIENT_UPDATED);
            out.flush();
        }
        return login;
    }

    /** Answers a configs request, or returns the login the client sends; null when the client leaves before either. */
    private static @Nullable ClientLogin open(DataInputStream in, OutputStream out, SessionProxy proxy)
            throws IOException {
        byte[] body;
        while ((body = Session.readFrame(in)) != null) {
            int opcode = body[0] & 0xFF;
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L785-L789
            if (opcode == PreLoginConfigsRequest.OPCODE && body.length < 3) {
                out.write(LiveFrames.serverFrame(proxy.clientConfigs()));
                out.flush();
                return null;
            }
            if (opcode == OPCODE_LOGIN && body.length - 1 >= MIN_LOGIN_PAYLOAD) {
                return ClientLogin.parse(body);
            }
        }
        return null;
    }
}
