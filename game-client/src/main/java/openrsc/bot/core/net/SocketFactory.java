package openrsc.bot.core.net;

import java.io.IOException;
import java.net.Socket;

@FunctionalInterface
public interface SocketFactory {

    Socket connect(String host, int port) throws IOException;

    default String describe() {
        return "socket";
    }
}
