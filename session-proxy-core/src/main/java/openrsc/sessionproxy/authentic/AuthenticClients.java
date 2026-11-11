package openrsc.sessionproxy.authentic;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import openrsc.bot.core.login.LoginCapabilities;
import openrsc.bot.core.login.LoginPacket;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.sessionproxy.ClientLogin;
import openrsc.sessionproxy.ClientProtocol;
import openrsc.sessionproxy.OwnLogin;
import openrsc.sessionproxy.ProxyKey;
import openrsc.sessionproxy.SessionProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The authentic RSC client protocol of version 235, which APOS speaks, translated to the custom protocol the proxy
 * speaks with the server.
 *
 * <p>An authentic client encrypts its login with the key built into it, so it is changed to carry the proxy's own
 * {@link ProxyKey}. The proxy reads the password and ISAAC keys from the login, logs the account in as a custom
 * client of the authentic client's limits (or joins its running session), and from then on translates every packet
 * both ways.
 */
public final class AuthenticClients implements ClientProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticClients.class);

    private static final int CUSTOM_VERSION = 10010;
    private static final String MAP_HASH = "63";
    private static final int CODE_INVALID_CREDENTIALS = 3;
    private static final int CODE_CLIENT_UPDATED = 5;

    private final ProxyKey key;

    public AuthenticClients(ProxyKey key) {
        this.key = key;
    }

    // A custom client's first frame has a two-byte length, under 512 for a login, so its first byte is 0 or 1.
    @Override
    public boolean recognizes(byte[] head) {
        if (head.length < 3 || (head[0] & 0xFF) < 2) {
            return false;
        }
        int opcode = head[2] & 0xFF;
        return opcode == AuthenticLogin.OPCODE || opcode == AuthenticLogin.OPCODE_RELOGIN;
    }

    @Override
    public void serve(Socket socket, DataInputStream in, SessionProxy proxy) throws IOException {
        byte[] body = AuthenticFrames.read(in, null);
        if (body == null) {
            return;
        }
        AuthenticLogin login;
        try {
            login = AuthenticLogin.parse(body, key);
        } catch (AuthenticLogin.WrongKeyException e) {
            LOG.warn(
                    "{} refused: its login is not encrypted with the proxy's key; give the client the key in {}",
                    socket.getRemoteSocketAddress(),
                    ProxyKey.FILE);
            write(socket, CODE_CLIENT_UPDATED);
            return;
        } catch (IOException e) {
            LOG.info("{} refused: {}", socket.getRemoteSocketAddress(), e.getMessage());
            write(socket, CODE_INVALID_CREDENTIALS);
            return;
        }
        PreLoginConfigs server = proxy.serverConfigs().configs();
        ClientLogin custom = ClientLogin.of(LoginPacket.build(
                login.username(),
                login.password(),
                CUSTOM_VERSION,
                server.rsaExponent(),
                server.rsaModulus(),
                OwnLogin.IDENTITY,
                MAP_HASH,
                LoginCapabilities.AUTHENTIC,
                0L));
        String label = custom.username() + " v" + login.version() + " " + socket.getRemoteSocketAddress();
        proxy.admit(
                custom,
                socket,
                new AuthenticWire(in, login.keys(), proxy.serverData().itemDefs(), server),
                label);
    }

    private static void write(Socket socket, int code) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(code);
        out.flush();
    }
}
