package openrsc.livemap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SameOriginTest {

    @Test
    void aPageOfTheGuiItselfMayOpenTheSocket() {
        assertTrue(SameOrigin.allowed("https://gui.example.com", "gui.example.com"));
        assertTrue(SameOrigin.allowed("https://GUI.example.com", "gui.example.com"));
        assertTrue(SameOrigin.allowed("http://localhost:5173", "localhost:5173"));
    }

    @Test
    void aPageOfAnyOtherOriginMayNot() {
        assertFalse(SameOrigin.allowed("https://evil.example.com", "gui.example.com"));
        assertFalse(SameOrigin.allowed("https://gui.example.com.evil.net", "gui.example.com"));
        assertFalse(SameOrigin.allowed("http://localhost:5173", "localhost:8765"));
        assertFalse(SameOrigin.allowed("null", "gui.example.com"), "a sandboxed frame");
        assertFalse(SameOrigin.allowed("https://gui.example.com", null));
    }
}
