package openrsc.sessionproxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether a request's {@code Host} header names this machine as only it or a browser on it would: {@code localhost} or
 * an IP address. A page of another web site can rebind its own domain to this machine's address and so reach a
 * server here as its own site; its requests still name that domain.
 */
public final class HostNames {

    private HostNames() {}

    /** Whether {@code host}, a {@code Host} header with or without its port, is {@code localhost} or an IP address. */
    public static boolean localOrAddress(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String name = name(host).toLowerCase(Locale.ROOT);
        if (name.equals("localhost")) {
            return true;
        }
        try {
            InetAddress.ofLiteral(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Whether {@code host}, a {@code Host} header with or without its port, is one of {@code names}, in lower case. */
    public static boolean named(@Nullable String host, Set<String> names) {
        return host != null && names.contains(name(host).toLowerCase(Locale.ROOT));
    }

    private static String name(String host) {
        String h = host.strip();
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close < 0 ? h : h.substring(1, close);
        }
        int colon = h.lastIndexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    /** {@code address} as a URL's authority: an IPv6 address in brackets, then the port. */
    public static String authority(InetSocketAddress address) {
        String host = address.getHostString();
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + address.getPort();
    }
}
