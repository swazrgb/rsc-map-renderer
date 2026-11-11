package openrsc.livemap.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * One password for every viewer of the map. A viewer who logs in keeps a cookie holding a token derived from the
 * password and a context, so it survives a restart and a new password voids it. With no password every viewer counts
 * as logged in, and there is nothing to log in to.
 */
public final class PasswordAuth {

    /** The request attribute {@link AuthFilter} records whether the request's viewer is logged in under. */
    public static final String AUTHED_ATTR = "livemap.authed";

    private final String cookie;
    private final @Nullable String password;
    private final @Nullable String sessionToken;

    /**
     * @param cookie the name of the cookie the token is kept in
     * @param tokenContext what the token is derived for, so two applications with one password issue different tokens
     * @param password null or blank for none
     */
    public PasswordAuth(String cookie, String tokenContext, @Nullable String password) {
        this.cookie = cookie;
        this.password = password == null || password.isBlank() ? null : password;
        this.sessionToken = this.password == null ? null : deriveToken(this.password, tokenContext);
    }

    private static String deriveToken(String password, String context) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(context.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public String cookie() {
        return cookie;
    }

    public boolean authEnabled() {
        return password != null;
    }

    public boolean checkPassword(@Nullable String candidate) {
        if (password == null || candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    /** The cookie's value for a logged-in viewer; null with no password. */
    public @Nullable String sessionToken() {
        return sessionToken;
    }

    public boolean authed(HttpServletRequest request) {
        return authedByToken(tokenFrom(request));
    }

    public boolean authedByToken(@Nullable String token) {
        if (!authEnabled()) {
            return true;
        }
        return token != null
                && sessionToken != null
                && MessageDigest.isEqual(
                        sessionToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    public @Nullable String tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (cookie.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
