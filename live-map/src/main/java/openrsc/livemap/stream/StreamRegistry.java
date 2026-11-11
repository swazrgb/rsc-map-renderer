package openrsc.livemap.stream;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * Every open stream client, and broadcasts of one event to them as {@code {"e": event, "d": data}}. Broadcasts and
 * the {@code has*} checks count only clients taking live frames.
 */
@Component
public class StreamRegistry {

    private final Set<StreamClient> clients = ConcurrentHashMap.newKeySet();
    private final Set<StreamClient> anonymous = ConcurrentHashMap.newKeySet();
    private boolean shuttingDown;

    private final ObjectMapper mapper;

    public StreamRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public TextMessage envelope(String event, Object payload) {
        return new TextMessage(mapper.writeValueAsString(Map.of("e", event, "d", payload)));
    }

    /** Adds the client, or closes it once {@link #closeAll} has run. */
    public void register(StreamClient client) {
        synchronized (this) {
            if (shuttingDown) {
                client.close();
                return;
            }
            // Anonymous first: a broadcast that sees the client in clients must already see it as anonymous.
            if (!client.authed()) {
                anonymous.add(client);
            }
            clients.add(client);
        }
    }

    public void forget(StreamClient client) {
        clients.remove(client);
        anonymous.remove(client);
    }

    public boolean hasAnonymousSubscribers() {
        for (StreamClient client : anonymous) {
            if (client.liveFrames()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSubscribers() {
        for (StreamClient client : clients) {
            if (client.liveFrames()) {
                return true;
            }
        }
        return false;
    }

    public void closeAll() {
        synchronized (this) {
            shuttingDown = true;
        }
        for (StreamClient client : clients) {
            forget(client);
            client.close();
        }
    }

    public void broadcast(String event, Object data) {
        if (!hasSubscribers()) {
            return;
        }
        TextMessage msg = envelope(event, data);
        for (StreamClient client : clients) {
            if (client.liveFrames() && !client.offer(msg)) {
                forget(client);
            }
        }
    }

    public boolean hasAuthedSubscribers() {
        for (StreamClient client : clients) {
            if (client.liveFrames() && client.authed()) {
                return true;
            }
        }
        return false;
    }

    public void broadcastAuthed(String event, Object data) {
        if (!hasAuthedSubscribers()) {
            return;
        }
        TextMessage msg = envelope(event, data);
        for (StreamClient client : clients) {
            if (client.liveFrames() && client.authed() && !client.offer(msg)) {
                forget(client);
            }
        }
    }

    /** Sends {@code authedData} to logged-in viewers and {@code anonData} to the others. */
    public void broadcastScoped(String event, Object authedData, Object anonData) {
        if (!hasSubscribers()) {
            return;
        }
        TextMessage authedMsg = envelope(event, authedData);
        TextMessage anonMsg = hasAnonymousSubscribers() ? envelope(event, anonData) : null;
        for (StreamClient client : clients) {
            if (!client.liveFrames()) {
                continue;
            }
            TextMessage msg = anonymous.contains(client) ? anonMsg : authedMsg;
            if (msg != null && !client.offer(msg)) {
                forget(client);
            }
        }
    }
}
