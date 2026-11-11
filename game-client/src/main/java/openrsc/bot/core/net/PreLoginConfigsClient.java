package openrsc.bot.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.bot.core.login.PreLoginConfigsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PreLoginConfigsClient {

    private static final Logger LOG = LoggerFactory.getLogger(PreLoginConfigsClient.class);

    private static final int SESSION_ID_BYTES = 4;

    private PreLoginConfigsClient() {}

    /**
     * A configs reply: {@code body} is the packet as the server sent it, opcode first, so it can be passed on unchanged.
     */
    public record Result(PreLoginConfigs configs, byte[] body, long dialNanos, long appRttNanos) {}

    public static PreLoginConfigs fetch(SocketFactory factory, String host, int port) throws IOException {
        return fetchTimed(factory, host, port).configs();
    }

    public static Result fetchTimed(SocketFactory factory, String host, int port) throws IOException {
        return fetchTimed(factory, host, port, 0);
    }

    public static Result fetchTimed(SocketFactory factory, String host, int port, int readTimeoutMs)
            throws IOException {
        long dialStart = System.nanoTime();
        try (Socket sock = factory.connect(host, port);
                var rawIn = sock.getInputStream();
                var out = sock.getOutputStream()) {
            if (readTimeoutMs > 0) {
                sock.setSoTimeout(readTimeoutMs);
            }
            long dialNanos = System.nanoTime() - dialStart;

            var in = new DataInputStream(rawIn);
            in.readFully(new byte[SESSION_ID_BYTES]);

            ByteBuf req = PreLoginConfigsRequest.build();
            byte[] reqBytes = new byte[req.readableBytes()];
            req.readBytes(reqBytes);
            out.write(reqBytes);
            out.flush();
            long reqSent = System.nanoTime();

            int length = in.readUnsignedShort() - 2;
            long appRttNanos = System.nanoTime() - reqSent;
            if (length <= 0 || length > 100_000) {
                throw new IOException("bogus configs length " + length);
            }

            byte[] body = new byte[length];
            in.readFully(body);

            int opcode = body[0] & 0xFF;
            if (opcode != PreLoginConfigsRequest.OPCODE) {
                throw new IOException(
                        "expected opcode " + PreLoginConfigsRequest.OPCODE + " in configs response, got " + opcode);
            }

            ByteBuf payload = Unpooled.wrappedBuffer(body, 1, body.length - 1);
            PreLoginConfigs cfg = PreLoginConfigs.parse(payload);
            LOG.debug(
                    "Fetched server configs via {} from {}:{} — name=\"{}\", dialMs={}, appRttMs={}",
                    factory.describe(),
                    host,
                    port,
                    cfg.serverName(),
                    dialNanos / 1_000_000,
                    appRttNanos / 1_000_000);
            return new Result(cfg, body, dialNanos, appRttNanos);
        }
    }
}
