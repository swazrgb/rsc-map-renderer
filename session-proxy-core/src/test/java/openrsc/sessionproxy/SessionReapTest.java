package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import openrsc.bot.core.net.Connection;
import org.junit.jupiter.api.Test;

class SessionReapTest {

    private static final byte[] NOTHING = new byte[0];

    @Test
    void reaps_a_stalled_session_closing_its_clients_and_marking_it_dead() throws Exception {
        try (LocalLink server = LocalLink.open();
                LocalLink client = LocalLink.open()) {
            Connection serverConn = new Connection("test-server", server.near());
            Session session = Session.forTest("testuser", serverConn);
            assertNotNull(session.attach(client.near(), wire(client.near()), "test", NOTHING));

            assertFalse(session.reapIfStalled(Duration.ofSeconds(10).toNanos()));
            assertFalse(session.isDead());
            assertFalse(client.near().isClosed());

            session.setLastProgressNanosForTest(
                    System.nanoTime() - Duration.ofSeconds(30).toNanos());
            assertTrue(session.reapIfStalled(Duration.ofSeconds(15).toNanos()));

            assertTrue(session.isDead(), "a stalled session is dead, so the next login opens a new one");
            awaitClosed(client.near());
            assertTrue(serverConn.isClosed(), "the server link is torn down");
            assertFalse(session.reapIfStalled(0));
        }
    }

    @Test
    void holds_an_attached_session_forever_and_releases_it_once_every_client_stays_away() throws Exception {
        try (LocalLink server = LocalLink.open();
                LocalLink client = LocalLink.open()) {
            Connection serverConn = new Connection("test-server", server.near());
            Session session = Session.forTest("testuser", serverConn);
            Client attached = session.attach(client.near(), wire(client.near()), "test", NOTHING);
            assertNotNull(attached);

            session.setUnattachedSinceNanosForTest(
                    System.nanoTime() - Duration.ofHours(1).toNanos());
            assertFalse(
                    session.reapIfUnattached(Duration.ofSeconds(30).toNanos()),
                    "a session with a client is never idle");
            assertFalse(session.isDead());

            session.detach(attached);
            assertFalse(session.reapIfUnattached(Duration.ofSeconds(30).toNanos()), "held across a reconnect gap");
            assertFalse(session.isDead());
            assertFalse(serverConn.isClosed(), "the server link stays up while held");

            session.setUnattachedSinceNanosForTest(
                    System.nanoTime() - Duration.ofSeconds(31).toNanos());
            assertTrue(session.reapIfUnattached(Duration.ofSeconds(30).toNanos()));
            assertTrue(session.isDead());
            assertTrue(serverConn.isClosed(), "closing the server link logs the account out");
            assertFalse(session.reapIfUnattached(0));
        }
    }

    @Test
    void a_client_attaching_inside_the_window_resets_the_idle_clock() throws Exception {
        try (LocalLink server = LocalLink.open();
                LocalLink first = LocalLink.open();
                LocalLink second = LocalLink.open()) {
            Connection serverConn = new Connection("test-server", server.near());
            Session session = Session.forTest("testuser", serverConn);
            Client one = session.attach(first.near(), wire(first.near()), "first", NOTHING);
            assertNotNull(one);
            session.detach(one);

            session.setUnattachedSinceNanosForTest(
                    System.nanoTime() - Duration.ofSeconds(29).toNanos());
            Client two = session.attach(second.near(), wire(second.near()), "second", NOTHING);
            assertNotNull(two);
            assertFalse(session.reapIfUnattached(Duration.ofSeconds(30).toNanos()), "the stale clock must not reap it");

            session.detach(two);
            assertFalse(session.reapIfUnattached(Duration.ofSeconds(30).toNanos()));
            assertFalse(session.isDead());
        }
    }

    private static ClientWire wire(Socket socket) throws IOException {
        return ClientWire.custom(new DataInputStream(socket.getInputStream()));
    }

    private static void awaitClosed(Socket socket) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!socket.isClosed() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(socket.isClosed(), "the client's connection is closed");
    }

    private record LocalLink(ServerSocket listener, Socket near, Socket far) implements AutoCloseable {
        static LocalLink open() throws Exception {
            ServerSocket listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
            Socket near = new Socket();
            near.connect(listener.getLocalSocketAddress(), 2_000);
            Socket far = listener.accept();
            return new LocalLink(listener, near, far);
        }

        @Override
        public void close() throws Exception {
            near.close();
            far.close();
            listener.close();
        }
    }
}
