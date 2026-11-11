package openrsc.sessionproxy;

import java.net.InetSocketAddress;
import java.time.Duration;
import org.jetbrains.annotations.Nullable;

/**
 * Where the proxy listens for clients, the world it holds sessions on, and whose login goes upstream: the client's own,
 * as it sent it, unless the proxy writes its own ({@link #writingOwnLogins}).
 */
public final class ProxyConfig {

    private final InetSocketAddress listen;
    private final ServerIdentity server;
    private final Duration idleHold;
    private final @Nullable ProxyKey ownLogins;

    /**
     * @param idleHold how long a session stays logged in once its last client has left; zero holds it until the
     *     server ends it
     */
    public ProxyConfig(InetSocketAddress listen, World world, Duration idleHold) {
        this(listen, world.identity(), idleHold);
    }

    ProxyConfig(InetSocketAddress listen, ServerIdentity server, Duration idleHold) {
        this(listen, server, idleHold, null);
    }

    private ProxyConfig(
            InetSocketAddress listen, ServerIdentity server, Duration idleHold, @Nullable ProxyKey ownLogins) {
        this.listen = listen;
        this.server = server;
        this.idleHold = idleHold;
        this.ownLogins = ownLogins;
    }

    /**
     * This config, with the proxy writing every login it sends upstream itself ({@link OwnLogin}): clients are handed
     * {@code key} in the configs packet to encrypt their password with, and the server sees the proxy's login.
     */
    public ProxyConfig writingOwnLogins(ProxyKey key) {
        return new ProxyConfig(listen, server, idleHold, key);
    }

    public InetSocketAddress listen() {
        return listen;
    }

    ServerIdentity server() {
        return server;
    }

    public Duration idleHold() {
        return idleHold;
    }

    /** The key clients encrypt their password with when the proxy writes its own logins; null when it does not. */
    public @Nullable ProxyKey ownLogins() {
        return ownLogins;
    }
}
