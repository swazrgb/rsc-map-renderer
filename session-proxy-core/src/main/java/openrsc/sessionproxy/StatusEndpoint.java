package openrsc.sessionproxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves {@code GET} on each of its paths: whatever that path's supplier returns, as JSON; only to a request whose
 * {@code Host} is {@code localhost} or an IP address ({@link HostNames}).
 */
public final class StatusEndpoint implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StatusEndpoint.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;

    private StatusEndpoint(HttpServer server) {
        this.server = server;
    }

    /** Serves {@code GET /sessions}: whatever {@code rows} returns. */
    public static StatusEndpoint start(InetSocketAddress address, Supplier<?> rows) throws IOException {
        return start(address, Map.of("/sessions", rows));
    }

    public static StatusEndpoint start(InetSocketAddress address, Map<String, Supplier<?>> paths) throws IOException {
        HttpServer server = HttpServer.create(address, 0);
        StatusEndpoint endpoint = new StatusEndpoint(server);
        paths.forEach((path, rows) -> server.createContext(path, ex -> handle(ex, rows)));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info("Session proxy status on http://{} {}", HostNames.authority(server.getAddress()), paths.keySet());
        return endpoint;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange ex, Supplier<?> rows) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (!HostNames.localOrAddress(ex.getRequestHeaders().getFirst("Host"))) {
                ex.sendResponseHeaders(403, -1);
                return;
            }
            byte[] body = JSON.writeValueAsBytes(rows.get());
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } catch (RuntimeException e) {
            LOG.warn("status endpoint error", e);
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
