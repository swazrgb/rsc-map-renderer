package openrsc.sessionproxy.web;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import openrsc.sessionproxy.ClientLink;
import openrsc.sessionproxy.RscClients;
import openrsc.sessionproxy.SessionProxy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/**
 * The browser game client on the live map: the custom client protocol over a WebSocket ({@code binary} subprotocol),
 * each message a stretch of the byte stream a socket would carry. A connection joins the held session of the account
 * it logs in as, without the account's password ({@link RscClients#serveFromMap}).
 */
final class BrowserClients extends BinaryWebSocketHandler implements SubProtocolCapable {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserClients.class);

    private static final String INBOUND = "browserClients.inbound";

    private final SessionProxy proxy;

    BrowserClients(SessionProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of("binary");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        Inbound in = new Inbound();
        ws.getAttributes().put(INBOUND, in);
        Thread.ofVirtual().name("proxy-browser-" + ws.getId()).start(() -> serve(ws, in));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        ByteBuffer payload = message.getPayload();
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        inbound(ws).add(bytes);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        inbound(ws).end();
    }

    private void serve(WebSocketSession ws, Inbound in) {
        Link link = new Link(ws);
        try {
            RscClients.serveFromMap(new DataInputStream(in), link, proxy, String.valueOf(ws.getRemoteAddress()));
        } catch (IOException e) {
            LOG.debug("browser client {}: {}", ws.getId(), e.toString());
        } catch (RuntimeException e) {
            LOG.warn("browser client {} failed", ws.getId(), e);
        } finally {
            link.close();
        }
    }

    private static Inbound inbound(WebSocketSession ws) {
        return (Inbound) ws.getAttributes().get(INBOUND);
    }

    /** What the browser sent, as one stream; it ends when the WebSocket closes. */
    private static final class Inbound extends InputStream {

        private static final byte[] END = new byte[0];

        private final LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private byte @Nullable [] current;
        private int at;
        private boolean ended;

        void add(byte[] bytes) {
            if (bytes.length > 0) {
                chunks.add(bytes);
            }
        }

        void end() {
            chunks.add(END);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            byte[] chunk = current;
            while (chunk == null || at == chunk.length) {
                if (ended) {
                    return -1;
                }
                try {
                    chunk = chunks.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted reading a browser client");
                }
                if (chunk == END) {
                    ended = true;
                    return -1;
                }
                current = chunk;
                at = 0;
            }
            int n = Math.min(len, chunk.length - at);
            System.arraycopy(chunk, at, b, off, n);
            at += n;
            return n;
        }
    }

    /** The WebSocket as a session writes to it: what is written goes out as one message at each flush. */
    private static final class Link extends OutputStream implements ClientLink {

        private final WebSocketSession ws;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        Link(WebSocketSession ws) {
            this.ws = ws;
        }

        @Override
        public OutputStream output() {
            return this;
        }

        @Override
        public synchronized void write(int b) {
            pending.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            pending.write(b, off, len);
        }

        @Override
        public synchronized void flush() throws IOException {
            if (pending.size() == 0) {
                return;
            }
            byte[] bytes = pending.toByteArray();
            pending.reset();
            if (!ws.isOpen()) {
                throw new IOException("the WebSocket has closed");
            }
            try {
                ws.sendMessage(new BinaryMessage(bytes));
            } catch (IllegalStateException e) {
                throw new IOException("the WebSocket has closed", e);
            }
        }

        // Closing can wait on a send in progress, and a session closes its clients under its lock.
        @Override
        public void close() {
            Thread.ofVirtual().start(() -> {
                try {
                    ws.close();
                } catch (IOException ignored) {
                    // A WebSocket that cannot close cleanly is gone either way.
                }
            });
        }
    }
}
