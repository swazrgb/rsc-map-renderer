package openrsc.livemap.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthFilterTest {

    private static final Set<String> PRIVILEGED = Set.of("/api/admin", "/api/secrets");

    private static boolean requiresAuth(String path, String method) {
        return AuthFilter.requiresAuth(path, method, PRIVILEGED);
    }

    @Test
    void aPrivilegedReadCoversItsSubPaths() {
        assertTrue(requiresAuth("/api/admin", "GET"));
        assertTrue(requiresAuth("/api/admin/users", "GET"));
        assertTrue(requiresAuth("/api/admin/users/7", "GET"));
        assertTrue(requiresAuth("/api/secrets.json", "GET"));
    }

    @Test
    void aPrefixClaimsOnlyItsOwnPathSegment() {
        assertFalse(requiresAuth("/api/adminfoo", "GET"));
        assertFalse(requiresAuth("/api/admins/list", "GET"));
        assertFalse(requiresAuth("/api/bots/admin", "GET"));
    }

    @Test
    void ordinaryReadsStayOpenAndEveryWriteIsControl() {
        assertFalse(requiresAuth("/api/bots", "GET"));
        assertTrue(requiresAuth("/api/bots/walk", "POST"));
        assertTrue(requiresAuth("/api/bots/x", "DELETE"));
        assertTrue(requiresAuth("/api/bots/x", "PUT"));
        assertTrue(requiresAuth("/api/bots/x", "PATCH"));
    }

    @Test
    void loginStaysReachableWhileAnonymous() {
        assertFalse(requiresAuth("/api/auth/login", "POST"));
        assertFalse(requiresAuth("/api/auth/status", "GET"));
    }

    @Test
    void onlyGetHeadAndOptionsAreReads() {
        assertFalse(requiresAuth("/api/bots", "GET"));
        assertFalse(requiresAuth("/api/bots", "HEAD"));
        assertFalse(requiresAuth("/api/bots", "OPTIONS"));
        assertTrue(requiresAuth("/api/bots", "post"));
        assertTrue(requiresAuth("/api/bots", "get"));
        assertTrue(requiresAuth("/api/bots", "PROPFIND"));
        assertTrue(requiresAuth("/api/bots", "TRACE"));
        assertTrue(requiresAuth("/api/bots", "FOO"));
    }
}
