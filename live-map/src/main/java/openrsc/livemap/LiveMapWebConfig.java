package openrsc.livemap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import openrsc.livemap.auth.PasswordAuth;
import openrsc.livemap.live.StreamHandler;
import openrsc.livemap.stream.TopicHub;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Serves the map's stream at {@code /api/ws}, to pages of the map's own origin, recording whether each viewer is logged
 * in and where it connects from; and reads every polled topic again after a logged-in viewer's write to {@code /api}.
 */
@Configuration
@EnableWebSocket
public class LiveMapWebConfig implements WebSocketConfigurer, WebMvcConfigurer {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final PasswordAuth auth;
    private final StreamHandler stream;
    private final TopicHub topics;

    public LiveMapWebConfig(PasswordAuth auth, StreamHandler stream, TopicHub topics) {
        this.auth = auth;
        this.stream = stream;
        this.topics = topics;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public void afterCompletion(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            Object handler,
                            @Nullable Exception ex) {
                        if (!READ_METHODS.contains(request.getMethod())
                                && response.getStatus() < 400
                                && Boolean.TRUE.equals(request.getAttribute(PasswordAuth.AUTHED_ATTR))
                                && !request.getRequestURI().startsWith("/api/auth/")) {
                            topics.touchAll();
                        }
                    }
                })
                .addPathPatterns("/api/**");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(stream, "/api/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new SameOrigin(), new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            WebSocketHandler wsHandler,
                            Map<String, Object> attributes) {
                        attributes.put(
                                StreamHandler.AUTHED_ATTR,
                                request instanceof ServletServerHttpRequest servlet
                                        && auth.authed(servlet.getServletRequest()));
                        attributes.put(StreamHandler.PEER_ATTR, peer(request));
                        return true;
                    }

                    @Override
                    public void afterHandshake(
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            WebSocketHandler wsHandler,
                            @Nullable Exception exception) {}
                });
    }

    /**
     * The viewer's address, an IPv6 one as its /64: Cloudflare's report of it when the connection came from a local
     * tunnel, else the connection's own.
     */
    static String peer(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress hop = remote == null ? null : remote.getAddress();
        if (hop == null) {
            return "?";
        }
        String edge = request.getHeaders().getFirst("CF-Connecting-IP");
        if (hop.isLoopbackAddress() && edge != null) {
            try {
                return key(InetAddress.ofLiteral(edge.trim()));
            } catch (IllegalArgumentException e) {
                return "?";
            }
        }
        return key(hop);
    }

    static String key(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            return String.format(
                    "%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64", b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
        }
        return address.getHostAddress();
    }
}
