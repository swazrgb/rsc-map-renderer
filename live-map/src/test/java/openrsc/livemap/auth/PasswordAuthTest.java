package openrsc.livemap.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordAuthTest {

    private static PasswordAuth service(String password) {
        return new PasswordAuth("map_auth", "map-auth-v1", password);
    }

    @Test
    void tokenSurvivesRestart() {
        PasswordAuth before = service("hunter2");
        PasswordAuth after = service("hunter2");
        assertEquals(before.sessionToken(), after.sessionToken());
        assertTrue(after.authedByToken(before.sessionToken()));
    }

    @Test
    void tokenIsNotThePassword() {
        PasswordAuth s = service("hunter2");
        assertNotEquals("hunter2", s.sessionToken());
        assertFalse(s.authedByToken("hunter2"), "the raw password must not work as a cookie value");
    }

    @Test
    void passwordChangeInvalidatesOldTokens() {
        String oldToken = service("hunter2").sessionToken();
        assertFalse(service("hunter3").authedByToken(oldToken));
    }

    @Test
    void checksPassword() {
        PasswordAuth s = service("hunter2");
        assertTrue(s.checkPassword("hunter2"));
        assertFalse(s.checkPassword("wrong"));
        assertFalse(s.checkPassword(null));
    }

    @Test
    void rejectsGarbageTokens() {
        PasswordAuth s = service("hunter2");
        assertFalse(s.authedByToken(null));
        assertFalse(s.authedByToken(""));
        assertFalse(s.authedByToken("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }

    @Test
    void disabledAuthAcceptsEverything() {
        for (String pw : new String[] {null, "", "  "}) {
            PasswordAuth s = service(pw);
            assertFalse(s.authEnabled());
            assertNull(s.sessionToken());
            assertTrue(s.authedByToken(null));
            assertTrue(s.authedByToken("anything"));
            assertFalse(s.checkPassword("anything"), "nothing to log into when auth is disabled");
        }
    }
}
