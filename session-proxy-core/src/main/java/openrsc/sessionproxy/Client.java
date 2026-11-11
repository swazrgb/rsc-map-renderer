package openrsc.sessionproxy;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client attached to a session. What the session sends it is queued and written by a thread of its own, so a slow
 * client never holds up the session or the other clients; one that falls too far behind is disconnected.
 */
final class Client {

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private static final long MAX_BACKLOG_BYTES = 4L << 20;
    private static final int WRITE_BUFFER_BYTES = 16 * 1024;
    private static final byte[] END = new byte[0];

    private final ClientLink link;
    private final ClientWire wire;
    private final String label;
    private final boolean fromMap;
    private final long attachedNanos = System.nanoTime();
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private final AtomicLong backlog = new AtomicLong();
    private volatile @Nullable Thread writer;
    private volatile boolean closed;

    /** Guarded by the session: true until the client has had its snapshot. */
    boolean joining;

    /**
     * A client writing to {@code link}, not yet started.
     *
     * @param fromMap whether it attached through the live map, whose input a pause from the map lets through
     */
    Client(ClientLink link, ClientWire wire, String label, boolean fromMap, boolean joining) {
        this.link = link;
        this.wire = wire;
        this.label = label;
        this.fromMap = fromMap;
        this.joining = joining;
    }

    /** Starts writing to the client. Until then nothing is written, and the connection is left as it is. */
    void start() {
        writer = Thread.ofVirtual().name("proxy-client-" + label).start(this::writeLoop);
    }

    String label() {
        return label;
    }

    ClientWire wire() {
        return wire;
    }

    boolean fromMap() {
        return fromMap;
    }

    long attachedNanos() {
        return attachedNanos;
    }

    boolean isClosed() {
        return closed;
    }

    /** Queues a server packet of the custom protocol, in the form the client's wire puts it in. */
    void send(byte[] body) {
        // Encoded under the lock so that a wire which ciphers its packets does so in the order they are written.
        synchronized (this) {
            if (closed) {
                return;
            }
            byte[] bytes;
            try {
                bytes = wire.encode(body);
            } catch (RuntimeException e) {
                // A wire that cannot encode a packet ends its own client, never the session the others share.
                LOG.warn("[{}] cannot encode server packet {}; disconnecting it", label, body[0] & 0xFF, e);
                close();
                return;
            }
            sendRaw(bytes);
        }
    }

    /** Queues bytes exactly as they go on the wire. */
    void sendRaw(byte[] bytes) {
        if (closed || bytes.length == 0) {
            return;
        }
        if (backlog.addAndGet(bytes.length) > MAX_BACKLOG_BYTES) {
            LOG.info("[{}] fell {} bytes behind; disconnecting it", label, backlog.get());
            close();
            return;
        }
        queue.add(bytes);
    }

    /** Closes the connection once everything queued so far is written. */
    void finish() {
        queue.add(END);
    }

    /** Waits up to {@code millis} for what {@link #finish} left queued to be written, then closes regardless. */
    void awaitFinished(long millis) {
        Thread w = writer;
        try {
            if (w != null) {
                w.join(millis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        close();
    }

    void close() {
        closed = true;
        queue.clear();
        queue.add(END);
        try {
            link.close();
        } catch (IOException ignored) {
            // Closing a broken connection is what close is for.
        }
    }

    private void writeLoop() {
        try (OutputStream out = new BufferedOutputStream(link.output(), WRITE_BUFFER_BYTES)) {
            while (true) {
                byte[] next = queue.take();
                if (next == END) {
                    break;
                }
                out.write(next);
                backlog.addAndGet(-next.length);
                if (queue.isEmpty()) {
                    out.flush();
                }
            }
            out.flush();
        } catch (IOException e) {
            if (!closed) {
                LOG.info("[{}] write failed: {}", label, e.toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }
}
