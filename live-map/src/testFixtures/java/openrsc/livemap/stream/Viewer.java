package openrsc.livemap.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** A viewer on a fake socket that keeps every frame sent to it. */
public final class Viewer {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    public final CountDownLatch closed = new CountDownLatch(1);
    public final StreamClient client;

    public Viewer(boolean authed) {
        this(authed, null, "peer");
    }

    public Viewer(boolean authed, @Nullable CountDownLatch readable) {
        this(authed, readable, "peer");
    }

    /** A viewer at {@code peer} whose every send waits on {@code readable} first, when it is given. */
    public Viewer(boolean authed, @Nullable CountDownLatch readable, String peer) {
        client = new StreamClient(new Socket(readable), authed, peer);
    }

    /** The next topic frame's {@code d}, waiting up to 5 seconds; fails on a frame of another event. */
    public JsonNode next() throws InterruptedException {
        String frame = sent.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame, "expected a frame");
        JsonNode node = MAPPER.readTree(frame);
        assertEquals("t", node.get("e").stringValue());
        return node.get("d");
    }

    /** The {@code d} of the next topic frame {@code wanted} accepts, skipping the others. */
    public JsonNode nextWhere(Predicate<JsonNode> wanted) throws InterruptedException {
        while (true) {
            JsonNode d = next();
            if (wanted.test(d)) {
                return d;
            }
        }
    }

    /** The next frame as sent, or null when none comes within 400 ms. */
    public @Nullable String poll() throws InterruptedException {
        return sent.poll(400, TimeUnit.MILLISECONDS);
    }

    /** The next frame of {@code event}, skipping frames of other events. */
    public JsonNode next(String event) throws InterruptedException {
        while (true) {
            String frame = sent.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame, "expected a " + event + " frame");
            JsonNode node = MAPPER.readTree(frame);
            if (event.equals(node.get("e").stringValue())) {
                return node.get("d");
            }
        }
    }

    /** Keeps the text of every frame sent on it; open until closed. */
    private final class Socket implements WebSocketSession {

        private final @Nullable CountDownLatch readable;
        private int textLimit;
        private int binaryLimit;

        Socket(@Nullable CountDownLatch readable) {
            this.readable = readable;
        }

        @Override
        public String getId() {
            return "viewer";
        }

        @Override
        public @Nullable URI getUri() {
            return null;
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public @Nullable Principal getPrincipal() {
            return null;
        }

        @Override
        public @Nullable InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public @Nullable InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public @Nullable String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int limit) {
            textLimit = limit;
        }

        @Override
        public int getTextMessageSizeLimit() {
            return textLimit;
        }

        @Override
        public void setBinaryMessageSizeLimit(int limit) {
            binaryLimit = limit;
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return binaryLimit;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (readable != null) {
                try {
                    readable.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            sent.add(((TextMessage) message).getPayload());
        }

        @Override
        public boolean isOpen() {
            return closed.getCount() > 0;
        }

        @Override
        public void close() {
            closed.countDown();
        }

        @Override
        public void close(CloseStatus status) {
            closed.countDown();
        }
    }
}
