package openrsc.sessionproxy.authentic;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.sessionproxy.ClientWire;
import org.jetbrains.annotations.Nullable;

/**
 * An authentic client's connection: its packets unscrambled and translated to the custom protocol, and the server's
 * translated to protocol 235 and scrambled, each direction with an {@link Isaac} of its own seeded with the login's
 * keys. Both ciphers exist before the login response is written, so the client's first packets decode.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/merge_requests/4119">openrsc!4119</a>
 */
final class AuthenticWire implements ClientWire {

    private final DataInputStream in;
    private final Isaac fromClient;
    private final Isaac toClient;
    private final ToClient translate;
    private final ToServer toServer;

    AuthenticWire(DataInputStream in, int[] keys, ItemDefs items, PreLoginConfigs server) {
        this.in = in;
        this.fromClient = new Isaac(keys);
        this.toClient = new Isaac(keys);
        this.translate = new ToClient(items, server);
        this.toServer = new ToServer(server, translate::slotAmount);
    }

    @Override
    public byte @Nullable [] read() throws IOException {
        while (true) {
            byte[] body = AuthenticFrames.read(in, fromClient);
            if (body == null) {
                return null;
            }
            byte[] custom = toServer.translate(body);
            if (custom != null) {
                return custom;
            }
        }
    }

    @Override
    public byte[] encode(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] packet : translate.translate(body)) {
            out.writeBytes(AuthenticFrames.write(packet, toClient));
        }
        return out.toByteArray();
    }
}
