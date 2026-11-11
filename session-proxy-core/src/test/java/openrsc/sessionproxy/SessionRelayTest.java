package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import openrsc.bot.core.liveview.LiveFrames;
import openrsc.bot.core.net.Connection;
import openrsc.bot.core.world.Opcode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class SessionRelayTest {

    private static final int WALK = 187;
    private static final int LOGOUT = Opcode.OPCODE_LOGOUT.wire();
    private static final int HEARTBEAT = Opcode.OPCODE_HEARTBEAT.wire();
    private static final int COMMAND = Opcode.OPCODE_COMMAND.wire();
    private static final int MESSAGE = Opcode.SEND_SERVER_MESSAGE.wire();
    private static final int BOUNDARY = Opcode.SEND_NPC_COORDS.wire();
    private static final int LOGOUT_CONFIRM = Opcode.SEND_LOGOUT_REQUEST_CONFIRM.wire();

    private Link upstream;
    private Session session;

    @BeforeEach
    void open() throws Exception {
        upstream = Link.open();
        session = Session.forTest("bob", new Connection("test-server", upstream.near()));
    }

    @AfterEach
    void close() throws Exception {
        session.close();
        upstream.close();
    }

    @Test
    void sends_input_upstream_but_answers_heartbeats_itself() throws Exception {
        try (Link a = attach("a")) {
            a.send(HEARTBEAT);
            a.send(WALK, 1, 2);

            assertArrayEquals(body(WALK, 1, 2), upstream.readClientFrame());
        }
    }

    @Test
    void pause_blocks_every_other_clients_input_until_the_same_client_sends_it_again() throws Exception {
        try (Link a = attach("a");
                Link b = attach("b")) {
            a.send(COMMAND, "pause\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(a.readMessage().contains("paused"));
            assertTrue(b.readMessage().contains("paused by another client"));

            b.send(WALK, 9);
            a.send(WALK, 1);
            assertArrayEquals(body(WALK, 1), upstream.readClientFrame(), "b's walk never went upstream");

            a.send(COMMAND, "PAUSE\n".getBytes(StandardCharsets.US_ASCII));
            assertTrue(b.readMessage().contains("resumed"));
            b.send(WALK, 2);
            assertArrayEquals(body(WALK, 2), upstream.readClientFrame());
        }
    }

    @Test
    void the_pausing_client_leaving_resumes_the_others() throws Exception {
        try (Link a = attach("a");
                Link b = attach("b")) {
            a.send(COMMAND, "pause\n".getBytes(StandardCharsets.US_ASCII));
            b.readMessage();
            a.close();
            assertTrue(b.readMessage().contains("resumed"));
            b.send(WALK, 3);
            assertArrayEquals(body(WALK, 3), upstream.readClientFrame());
        }
    }

    @Test
    void a_client_joining_while_paused_is_told() throws Exception {
        try (Link a = attach("a")) {
            a.send(COMMAND, "pause\n".getBytes(StandardCharsets.US_ASCII));
            a.readMessage();
            try (Link b = attach("b")) {
                assertTrue(b.readMessage().contains("paused by another client"));
            }
        }
    }

    @Test
    void evicting_a_label_disconnects_only_its_clients() throws Exception {
        try (Link a = attach("a");
                Link b = attach("b")) {
            session.evict("b");

            assertThrows(EOFException.class, b::readServerFrame);
            assertEquals(1, session.status().clients());
            a.send(WALK, 5);
            assertArrayEquals(body(WALK, 5), upstream.readClientFrame());
        }
    }

    @Test
    void a_logout_while_others_stay_lets_only_that_client_go() throws Exception {
        try (Link a = attach("a");
                Link b = attach("b")) {
            b.send(LOGOUT);
            assertArrayEquals(
                    new byte[] {(byte) Opcode.SEND_LOGOUT_REQUEST_CONFIRM.wire()},
                    b.readServerFrame(),
                    "the confirmation the server sends a client it logs out");
            assertThrows(EOFException.class, b::readServerFrame);
            awaitTrue(() -> session.status().clients() == 1);

            a.send(WALK, 4);
            assertArrayEquals(body(WALK, 4), upstream.readClientFrame(), "the logout never went upstream");
        }
    }

    @Test
    void the_last_clients_logout_is_the_servers_to_decide() throws Exception {
        try (Link a = attach("a")) {
            a.send(LOGOUT);
            assertArrayEquals(body(LOGOUT), upstream.readClientFrame());
        }
    }

    @RepeatedTest(20)
    void the_last_client_gets_the_servers_logout_confirmation_however_its_threads_interleave() throws Exception {
        session.close();
        AtomicReference<Session> pumped = new AtomicReference<>();
        BooleanSupplier dead = () -> pumped.get().isDead();
        // The relay sees the session die only as it returns from sending the logout, and the client's writer gets to
        // write only once the session is dead: the server answers the logout and hangs up in between.
        try (Link server = Link.open(out -> new Held(out, () -> {}, () -> spinUntil(dead)));
                Link client = Link.open(out -> new Held(
                        out,
                        () -> {
                            awaitTrue(dead);
                            Thread.sleep(50);
                        },
                        () -> {}))) {
            pumped.set(Session.forTest("carol", new Connection("test-server", server.near())));
            session = pumped.get();
            session.startPumpForTest();
            Thread.ofVirtual().start(() -> {
                try {
                    pumped.get()
                            .serve(
                                    client.near(),
                                    ClientWire.custom(
                                            new DataInputStream(client.near().getInputStream())),
                                    "a",
                                    new byte[0]);
                } catch (IOException ignored) {
                    // The session closed the client.
                }
            });
            awaitTrue(() -> pumped.get().status().clients() == 1);

            server.sendServerFrame(BOUNDARY);
            client.send(LOGOUT);
            assertArrayEquals(body(LOGOUT), server.readClientFrame());
            server.sendServerFrame(LOGOUT_CONFIRM);
            server.far().close();

            assertArrayEquals(body(BOUNDARY), client.readServerFrame());
            assertArrayEquals(
                    body(LOGOUT_CONFIRM),
                    client.readServerFrame(),
                    "the confirmation the server sent before it hung up");
            assertThrows(EOFException.class, client::readServerFrame);
        }
    }

    @Test
    void a_joining_client_starts_at_the_next_tick_boundary_with_what_it_missed_while_none_watched() throws Exception {
        session.fanOut(MESSAGE, body(MESSAGE, 7));
        try (Link a = attach("a")) {
            awaitTrue(() -> session.status().clients() == 1);
            session.fanOut(WALK, body(WALK, 1));
            session.fanOut(BOUNDARY, body(BOUNDARY, 2));
            session.fanOut(WALK, body(WALK, 3));

            assertArrayEquals(body(BOUNDARY, 2), a.readServerFrame());
            assertArrayEquals(body(MESSAGE, 7), a.readServerFrame(), "the line heard while held");
            assertArrayEquals(body(WALK, 3), a.readServerFrame());
        }
    }

    private Link attach(String label) throws Exception {
        Link link = Link.open();
        int before = session.status().clients();
        Thread.ofVirtual().start(() -> {
            try {
                session.serve(
                        link.near(),
                        ClientWire.custom(new DataInputStream(link.near().getInputStream())),
                        label,
                        new byte[0]);
            } catch (IOException ignored) {
                // The test closed the client.
            }
        });
        awaitTrue(() -> session.status().clients() > before);
        return link;
    }

    private static byte[] body(int opcode, int... payload) {
        byte[] out = new byte[1 + payload.length];
        out[0] = (byte) opcode;
        for (int i = 0; i < payload.length; i++) {
            out[i + 1] = (byte) payload[i];
        }
        return out;
    }

    /** A stream that runs {@code before} ahead of each write it passes on, and {@code after} once it has. */
    private static final class Held extends FilterOutputStream {
        private final Pause before;
        private final Pause after;

        Held(OutputStream out, Pause before, Pause after) {
            super(out);
            this.before = before;
            this.after = after;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            before.runInterruptibly();
            out.write(b, off, len);
            after.runInterruptibly();
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }
    }

    private interface Pause {
        void run() throws InterruptedException;

        default void runInterruptibly() throws IOException {
            try {
                run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }
    }

    private static void spinUntil(BooleanSupplier cond) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitTrue(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(5);
        }
    }

    private record Link(ServerSocket listener, Socket near, Socket far) implements AutoCloseable {
        static Link open() throws Exception {
            return open(UnaryOperator.identity());
        }

        /** A link whose near end writes through {@code onOutput}. */
        static Link open(UnaryOperator<OutputStream> onOutput) throws Exception {
            ServerSocket listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            Socket near = new Socket() {
                @Override
                public OutputStream getOutputStream() throws IOException {
                    return onOutput.apply(super.getOutputStream());
                }
            };
            near.connect(listener.getLocalSocketAddress(), 2_000);
            Socket far = listener.accept();
            far.setSoTimeout(5_000);
            return new Link(listener, near, far);
        }

        void send(int opcode, int... payload) throws IOException {
            byte[] b = body(opcode, payload);
            send(b);
        }

        void send(int opcode, byte[] payload) throws IOException {
            byte[] b = new byte[1 + payload.length];
            b[0] = (byte) opcode;
            System.arraycopy(payload, 0, b, 1, payload.length);
            send(b);
        }

        private void send(byte[] b) throws IOException {
            byte[] framed = new byte[b.length + 2];
            framed[0] = (byte) (b.length >> 8);
            framed[1] = (byte) b.length;
            System.arraycopy(b, 0, framed, 2, b.length);
            far.getOutputStream().write(framed);
            far.getOutputStream().flush();
        }

        void sendServerFrame(int opcode) throws IOException {
            far.getOutputStream().write(LiveFrames.serverFrame(body(opcode)));
            far.getOutputStream().flush();
        }

        byte[] readServerFrame() throws IOException {
            DataInputStream in = new DataInputStream(far.getInputStream());
            byte[] b = new byte[in.readUnsignedShort() - 2];
            in.readFully(b);
            return b;
        }

        byte[] readClientFrame() throws IOException {
            DataInputStream in = new DataInputStream(far.getInputStream());
            byte[] b = new byte[in.readUnsignedShort()];
            in.readFully(b);
            return b;
        }

        String readMessage() throws IOException {
            byte[] b = readServerFrame();
            assertEquals(MESSAGE, b[0] & 0xFF);
            // Opcode, icon (int), type and info (a byte each), then the text.
            return new String(b, 7, b.length - 8, StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws Exception {
            near.close();
            far.close();
            listener.close();
        }
    }
}
