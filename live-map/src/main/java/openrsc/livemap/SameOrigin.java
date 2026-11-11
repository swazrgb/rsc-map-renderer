package openrsc.livemap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Refuses a browser handshake from a page of another origin, whose request would still carry the viewer's cookie. The
 * check compares host and port only, as TLS may end at a proxy in front; a handshake without an Origin header comes
 * from no browser page.
 */
public final class SameOrigin implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(SameOrigin.class);

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || allowed(origin, request.getHeaders().getFirst(HttpHeaders.HOST))) {
            return true;
        }
        LOG.info(
                "ws handshake refused: Origin {} is not Host {}",
                origin,
                request.getHeaders().getFirst(HttpHeaders.HOST));
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    /** Whether a page of {@code origin} is the site a request with {@code host} as its Host header went to. */
    public static boolean allowed(String origin, @Nullable String host) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getHost() == null || host == null) {
            return false;
        }
        String authority = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
        return authority.equalsIgnoreCase(host.trim());
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            @Nullable Exception exception) {}
}
