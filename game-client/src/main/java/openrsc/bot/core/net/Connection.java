package openrsc.bot.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import openrsc.bot.core.runtime.Threads;
import openrsc.bot.core.world.Opcode;
import openrsc.bot.core.world.PacketDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Connection implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Connection.class);

    private static final int HEARTBEAT_INTERVAL_S = 5;

    private static final int MAX_INBOUND_QUEUE = 8192;

    private final String name;
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private volatile long bytesWritten;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final LinkedBlockingQueue<InboundEvent> inbound = new LinkedBlockingQueue<>(MAX_INBOUND_QUEUE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final TickArrival tickArrival = new TickArrival();

    private volatile long lastSentNanos;
    private volatile Thread readerThread;
    private volatile ScheduledFuture<?> heartbeatTask;

    public Connection(String name, Socket socket) throws IOException {
        this.name = name;
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
        this.lastSentNanos = System.nanoTime();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public LinkedBlockingQueue<InboundEvent> inbound() {
        return inbound;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public TickArrival.Summary tickArrival() {
        return tickArrival.last();
    }

    public void setReadTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    public int readRawByte() throws IOException {
        return in.readUnsignedByte();
    }

    public byte[] readRawBytes(int n) throws IOException {
        byte[] buf = new byte[n];
        in.readFully(buf);
        return buf;
    }

    public long bytesWritten() {
        return bytesWritten;
    }

    public int serverPort() {
        return socket.getPort();
    }

    private static final java.util.concurrent.atomic.AtomicLong SESSION_IDS =
            new java.util.concurrent.atomic.AtomicLong();

    private final long sessionId = SESSION_IDS.incrementAndGet();

    public long sessionId() {
        return sessionId;
    }

    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void send(ByteBuf framed) throws IOException {
        byte[] tmp = new byte[framed.readableBytes()];
        framed.readBytes(tmp);
        if (LOG.isDebugEnabled()) {
            logOutbound(tmp);
        }
        writeLock.lock();
        try {
            out.write(tmp);
            out.flush();
            bytesWritten += tmp.length;
            lastSentNanos = System.nanoTime();
        } finally {
            writeLock.unlock();
        }
    }

    private void logOutbound(byte[] tmp) {
        if (tmp.length < 3) {
            LOG.debug("[{}] OUT raw {}B: {}", name, tmp.length, hex(tmp, 0, tmp.length));
            return;
        }
        int opcode = tmp[2] & 0xFF;
        LOG.debug("[{}] OUT opcode={} len={} payload=[{}]", name, opcode, tmp.length - 3, hex(tmp, 3, tmp.length));
    }

    private static String hex(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder((to - from) * 3);
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b[i] & 0xF, 16));
        }
        return sb.toString();
    }

    public void startPacketReader() {
        if (readerThread != null) {
            throw new IllegalStateException("packet reader already started");
        }
        Thread t = Threads.named("conn-reader-" + name).start(this::readerLoop);
        this.readerThread = t;
    }

    public void startHeartbeat(ScheduledExecutorService scheduler) {
        if (heartbeatTask != null) {
            throw new IllegalStateException("heartbeat already started");
        }
        this.heartbeatTask = scheduler.scheduleAtFixedRate(
                this::heartbeatTick, HEARTBEAT_INTERVAL_S, HEARTBEAT_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void heartbeatTick() {
        try {
            long idleNs = System.nanoTime() - lastSentNanos;
            if (idleNs < TimeUnit.SECONDS.toNanos(HEARTBEAT_INTERVAL_S)) {
                return;
            }
            if (closed.get()) {
                return;
            }
            ByteBuf p = Unpooled.buffer(3);
            p.writeShort(1);
            p.writeByte(Opcode.OPCODE_HEARTBEAT.wire());
            send(p);
        } catch (IOException e) {
            LOG.debug("[{}] heartbeat send failed: {}", name, e.toString());
            close("heartbeat failed", e);
        } catch (RuntimeException e) {
            LOG.warn("[{}] heartbeat tick threw", name, e);
        }
    }

    private void readerLoop() {
        try {
            while (!closed.get()) {
                int length = in.readUnsignedShort() - 2;
                if (length <= 0 || length > 100_000) {
                    throw new IOException("invalid packet length " + (length + 2));
                }
                byte[] body = new byte[length];
                in.readFully(body);
                int opcode = body[0] & 0xFF;
                if (PacketDispatcher.beginsTickBundle(opcode)) {
                    TickArrival.Summary s = tickArrival.note(System.nanoTime());
                    if (s != null) {
                        LOG.info(
                                "[{}] tick arrival: p50={}ms p95={}ms max={}ms late={}/{} (>1.5x p50)"
                                        + " totalLate={}/{} localPort={}",
                                name,
                                s.p50Ms(),
                                s.p95Ms(),
                                s.maxMs(),
                                s.late(),
                                s.gaps(),
                                s.totalLate(),
                                s.totalGaps(),
                                socket.getLocalPort());
                    }
                }
                ByteBuf payload = Unpooled.wrappedBuffer(body, 1, body.length - 1);
                if (!inbound.offer(new InboundEvent.Packet(opcode, payload))) {
                    throw new IOException("inbound queue overflow; consumer stalled");
                }
            }
        } catch (EOFException eof) {
            close("eof", null);
        } catch (IOException ioe) {
            close("io: " + ioe.getMessage(), ioe);
        } catch (Throwable t) {
            LOG.warn("[{}] reader threw", name, t);
            close("reader exception", t);
        } finally {
            close("reader exited", null);
        }
    }

    @Override
    public void close() {
        close("local close", null);
    }

    public void close(String reason, Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is already broken is exactly the case close() exists to handle, and
            // the Disconnected sentinel below tells the consumer everything it needs.
        }
        InboundEvent disconnect = new InboundEvent.Disconnected(reason, cause);
        while (!inbound.offer(disconnect)) {
            if (inbound.poll() == null) {
                break;
            }
        }
    }
}
