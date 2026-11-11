package openrsc.bot.core.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class DirectSocketFactory implements SocketFactory {

    private final int connectTimeoutMs;

    public DirectSocketFactory(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public DirectSocketFactory() {
        this(15_000);
    }

    @Override
    public String describe() {
        return "direct";
    }

    @Override
    public Socket connect(String host, int port) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        return s;
    }
}
