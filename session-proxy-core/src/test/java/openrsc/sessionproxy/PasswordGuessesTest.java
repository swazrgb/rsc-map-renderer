package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class PasswordGuessesTest {

    @Test
    void an_address_gets_ten_wrong_passwords_and_loopback_any_number() throws Exception {
        PasswordGuesses guesses = new PasswordGuesses();
        InetAddress remote = InetAddress.getByAddress(new byte[] {10, 0, 0, 7});
        InetAddress local = InetAddress.getLoopbackAddress();
        for (int i = 0; i < 10; i++) {
            assertFalse(guesses.exhausted(remote));
            guesses.wrong(remote);
            guesses.wrong(local);
        }
        assertTrue(guesses.exhausted(remote));
        assertFalse(guesses.exhausted(local));
        assertFalse(guesses.exhausted(InetAddress.getByAddress(new byte[] {10, 0, 0, 8})), "per address");
    }
}
