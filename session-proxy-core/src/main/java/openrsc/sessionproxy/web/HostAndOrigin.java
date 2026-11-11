package openrsc.sessionproxy.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import openrsc.livemap.SameOrigin;
import openrsc.sessionproxy.HostNames;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps other web sites' pages off the live map, which has no login. A page can send a request to any address,
 * {@code 127.0.0.1} too, so the map answers only a request whose {@code Host} is {@code localhost}, an IP address
 * ({@link HostNames}) or a name the proxy's settings list, never a domain that rebound itself to this machine. A write ({@code POST}, {@code DELETE}, …)
 * must carry JSON, which a page of another site cannot send without the browser asking first, and, when it has an
 * {@code Origin}, the map's own. No page of another site may frame the map's pages. The WebSocket handshakes check
 * their Origin themselves ({@link SameOrigin}).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
final class HostAndOrigin extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(HostAndOrigin.class);

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> names;

    /** @param names the host names the map also answers to, in lower case */
    HostAndOrigin(Set<String> names) {
        this.names = Set.copyOf(names);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", "frame-ancestors 'self'");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        String refused = refusal(
                names,
                request.getMethod(),
                request.getHeader(HttpHeaders.HOST),
                request.getHeader(HttpHeaders.ORIGIN),
                request.getContentType());
        if (refused != null) {
            LOG.info("refused {} {}: {}", request.getMethod(), request.getRequestURI(), refused);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(refused);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Why a request with these headers is refused; null when it is let through. */
    static @Nullable String refusal(
            Set<String> names,
            String method,
            @Nullable String host,
            @Nullable String origin,
            @Nullable String contentType) {
        if (!HostNames.localOrAddress(host) && !HostNames.named(host, names)) {
            return "the map answers only to localhost, an IP address or a name in hosts, not " + host;
        }
        if (READ_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return null;
        }
        if (!json(contentType)) {
            return "a write to the map carries JSON";
        }
        if (origin != null && !SameOrigin.allowed(origin, host)) {
            return "a page of " + origin + " cannot play from the map";
        }
        return null;
    }

    private static boolean json(@Nullable String contentType) {
        try {
            return contentType != null
                    && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
