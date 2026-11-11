package openrsc.sessionproxy;

import java.io.IOException;
import java.net.Socket;
import openrsc.bot.core.net.DirectSocketFactory;
import org.jetbrains.annotations.Nullable;

/**
 * Opens the proxy's connections to the game server. Every connection {@link #connect} returns for an account is
 * followed by exactly one {@link #answered} for it, once the server has replied to the login sent over it or the login
 * failed without a reply; a {@code connect} that throws is not. Called from many threads at once, but never twice at
 * once for one account.
 */
@FunctionalInterface
public interface Upstream {

    /**
     * A connection to {@code host:port}, the world's server, for {@code account}'s session
     * ({@link ClientLogin#account}), or for no account when it is null (the pre-login configs). It reaches that server
     * and no other. May block until a connection can be opened.
     *
     * @throws openrsc.bot.core.session.LoginRejectedException to refuse the login with its code, without dialling
     */
    Socket connect(@Nullable String account, String host, int port) throws IOException;

    /**
     * The server's reply to the login sent over {@code account}'s last connection: its response byte, or -1 when none
     * came. Returns whether to log in again over a new connection.
     */
    default boolean answered(String account, int code) {
        return false;
    }

    /** What {@code account}'s connections go through, for the log. */
    default String describe(String account) {
        return "direct";
    }

    /** Dials the server directly, giving up on a connection after {@code connectTimeoutMs}. */
    static Upstream direct(int connectTimeoutMs) {
        DirectSocketFactory direct = new DirectSocketFactory(connectTimeoutMs);
        return (account, host, port) -> direct.connect(host, port);
    }
}
