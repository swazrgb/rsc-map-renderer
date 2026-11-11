package openrsc.livemap.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Records under {@link PasswordAuth#AUTHED_ATTR} whether a request to {@code /api} comes from a logged-in viewer, and
 * refuses one that needs a logged-in viewer and has none: every write, and the reads the {@link AuthPolicy} names. A
 * request whose path is not canonical (a dot segment, a doubled or encoded slash) is refused outright, since the rules
 * match on the path the request is routed by.
 */
@Component
@Order(1)
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final String ROUTED_PATH_ATTR = AuthFilter.class.getName() + ".routedPath";

    private final PasswordAuth auth;
    private final Set<String> privilegedReads;

    public AuthFilter(PasswordAuth auth, ObjectProvider<AuthPolicy> policy) {
        this.auth = auth;
        this.privilegedReads =
                policy.getIfAvailable(() -> new AuthPolicy(Set.of())).privilegedReads();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return routedPath(request).filter(path -> !path.startsWith("/api/")).isPresent();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> path = routedPath(request);
        if (path.isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "non-canonical request path");
            return;
        }
        boolean authed = auth.authed(request);
        request.setAttribute(PasswordAuth.AUTHED_ATTR, authed);
        if (!authed && requiresAuth(path.get(), request.getMethod(), privilegedReads)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        chain.doFilter(request, response);
    }

    static Optional<String> routedPath(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Optional<String> cached = (Optional<String>) request.getAttribute(ROUTED_PATH_ATTR);
        if (cached == null) {
            cached = resolve(request);
            request.setAttribute(ROUTED_PATH_ATTR, cached);
        }
        return cached;
    }

    private static Optional<String> resolve(HttpServletRequest request) {
        PathContainer routed;
        try {
            routed = ServletRequestPathUtils.parse(request).pathWithinApplication();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        StringBuilder joined = new StringBuilder();
        for (PathContainer.Element element : routed.elements()) {
            joined.append(
                    element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
        }
        String path = joined.toString();
        String prefix = Objects.toString(ServletRequestPathUtils.getServletPathPrefix(request), "");
        String container = request.getServletPath() + Objects.toString(request.getPathInfo(), "");
        return (prefix + path).equals(container) ? Optional.of(path) : Optional.empty();
    }

    /** Whether a request to {@code path} by {@code method} needs a logged-in viewer. */
    public static boolean requiresAuth(String path, String method, Set<String> privilegedReads) {
        if (path.startsWith("/api/auth/")) {
            return false;
        }
        if (!READ_METHODS.contains(method)) {
            return true;
        }
        for (String prefix : privilegedReads) {
            if (path.equals(prefix) || path.startsWith(prefix + "/") || path.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }
}
