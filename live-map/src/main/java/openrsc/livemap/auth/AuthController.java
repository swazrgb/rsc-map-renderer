package openrsc.livemap.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logging in to the map: {@code POST /api/auth/login} with {@code {"password": …}} sets the {@link PasswordAuth}
 * cookie, {@code POST /api/auth/logout} clears it, and {@code GET /api/auth/status} says whether the viewer is logged
 * in and whether there is a password at all.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Duration TTL = Duration.ofDays(3650);

    private final PasswordAuth auth;

    public AuthController(PasswordAuth auth) {
        this.auth = auth;
    }

    public record LoginRequest(String password) {}

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body) {
        String pw = body == null ? null : body.password();
        if (!auth.checkPassword(pw)) {
            return ResponseEntity.status(401).body(Map.of("authed", false, "authEnabled", auth.authEnabled()));
        }
        ResponseCookie cookie = ResponseCookie.from(auth.cookie(), auth.sessionToken())
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(TTL)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("authed", true, "authEnabled", true));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        ResponseCookie cleared = ResponseCookie.from(auth.cookie(), "")
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cleared.toString())
                .body(Map.of("authed", false, "authEnabled", auth.authEnabled()));
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        return Map.of("authed", auth.authed(request), "authEnabled", auth.authEnabled());
    }
}
