package openrsc.livemap.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** One viewer's socket, with a sender thread of its own, so a slow viewer delays nobody else. */
public final class StreamClient {

    private static final Logger LOG = LoggerFactory.getLogger(StreamClient.class);

    private static final int QUEUE_CAP = 1024;

    private static final long QUEUE_CHARS_CAP = 16L << 20;

    /** Subscriptions a viewer may open in a burst, and how many more it earns each second. */
    private static final double SUBSCRIBE_BURST = 32;

    private static final double SUBSCRIBES_PER_SECOND = 4;

    private final WebSocketSession session;
    private final boolean authed;
    private final String peer;
    private final BlockingQueue<TextMessage> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
    private final Thread sender;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong queuedChars = new AtomicLong();
    private volatile boolean liveFrames = true;

    @Nullable
    private List<TextMessage> held;

    private double subscribeTokens = SUBSCRIBE_BURST;
    private long tokensAtNanos = System.nanoTime();

    /** {@code peer} is the viewer's address: anonymous viewers at one address share their caps. */
    public StreamClient(WebSocketSession session, boolean authed, String peer) {
        this.session = session;
        this.authed = authed;
        this.peer = peer;
        this.sender = new Thread(this::drain, "map-stream-" + session.getId());
        this.sender.setDaemon(true);
        this.sender.start();
    }

    public boolean authed() {
        return authed;
    }

    public String peer() {
        return peer;
    }

    /** Whether the viewer may open another subscription now; each one spends a token of a slowly refilled burst. */
    synchronized boolean takeSubscribeToken() {
        long now = System.nanoTime();
        subscribeTokens =
                Math.min(SUBSCRIBE_BURST, subscribeTokens + (now - tokensAtNanos) / 1e9 * SUBSCRIBES_PER_SECOND);
        tokensAtNanos = now;
        if (subscribeTokens < 1) {
            return false;
        }
        subscribeTokens--;
        return true;
    }

    /** Whether the viewer takes the live frames; off while it is sent something else, such as a replay. */
    public boolean liveFrames() {
        return liveFrames;
    }

    public void setLiveFrames(boolean v) {
        liveFrames = v;
    }

    public boolean isOpen() {
        return !closed.get() && session.isOpen();
    }

    /**
     * Queues a frame for this viewer's own sender thread and never waits: a viewer whose backlog passes the queue's
     * frame or size cap is dropped, its socket closed on another thread, so a slow viewer costs nobody else time.
     */
    public boolean offer(TextMessage msg) {
        synchronized (this) {
            if (held == null) {
                return enqueue(msg);
            }
            if (held.size() >= QUEUE_CAP) {
                dropSlow();
                return false;
            }
            held.add(msg);
            return !closed.get();
        }
    }

    /** Keeps every frame offered from now on back until {@link #release}, for a seed built after its base was taken. */
    public synchronized void hold() {
        if (held == null) {
            held = new ArrayList<>();
        }
    }

    /** Queues {@code first}, then the frames held back since {@link #hold}, and lets frames through again. */
    public synchronized void release(List<TextMessage> first) {
        List<TextMessage> later = held == null ? List.of() : held;
        held = null;
        for (TextMessage m : first) {
            enqueue(m);
        }
        for (TextMessage m : later) {
            enqueue(m);
        }
    }

    private boolean enqueue(TextMessage msg) {
        if (closed.get()) {
            return false;
        }
        int chars = msg.getPayloadLength();
        if (queuedChars.addAndGet(chars) <= QUEUE_CHARS_CAP && queue.offer(msg)) {
            return true;
        }
        queuedChars.addAndGet(-chars);
        dropSlow();
        return false;
    }

    private void dropSlow() {
        LOG.info("stream client {}: outbound queue full — dropping slow viewer", session.getId());
        if (shut()) {
            Thread.ofVirtual().name("map-stream-close-" + session.getId()).start(this::closeSession);
        }
    }

    public void close() {
        if (shut()) {
            closeSession();
        }
    }

    private boolean shut() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        queue.clear();
        queuedChars.set(0);
        sender.interrupt();
        return true;
    }

    private void closeSession() {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (IOException | RuntimeException ignored) {
            // Already closed / transport gone — nothing to release.
        }
    }

    private void drain() {
        try {
            while (!closed.get()) {
                TextMessage msg = queue.take();
                queuedChars.addAndGet(-msg.getPayloadLength());
                session.sendMessage(msg);
            }
        } catch (InterruptedException e) {
            // close() interrupted us — fall through.
        } catch (IOException | RuntimeException e) {
            // Connection died mid-send (tab closed, network drop). Close below; the container's close
            // callback tears down the rest.
        }
        close();
    }
}
