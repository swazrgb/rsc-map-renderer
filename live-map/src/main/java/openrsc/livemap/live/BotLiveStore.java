package openrsc.livemap.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import openrsc.livemap.dto.BotLiveDto;
import org.springframework.stereotype.Component;

/** Each bot's latest record by username, and which changed since the last {@link #drainDirty}. Thread-safe. */
@Component
public class BotLiveStore {

    private final ConcurrentHashMap<String, BotLiveDto> latest = new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public void park(BotLiveDto dto) {
        latest.put(dto.username(), dto);
        dirty.add(dto.username());
    }

    public List<BotLiveDto> drainDirty() {
        if (dirty.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(dirty);
        List<BotLiveDto> out = new ArrayList<>(keys.size());
        for (String user : keys) {
            dirty.remove(user);
            BotLiveDto dto = latest.get(user);
            if (dto != null) {
                out.add(dto);
            }
        }
        return out;
    }

    public BotLiveDto get(String username) {
        return latest.get(username);
    }

    public List<BotLiveDto> all() {
        return new ArrayList<>(latest.values());
    }
}
