package openrsc.livemap.items;

import java.util.LinkedHashMap;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The world's items: {@code /api/items/defs}, each item's name by id; {@code /api/items/defs-full}, also its base
 * price, its command, whether it stacks, and whether it is worn or wielded.
 */
@RestController
@RequestMapping("/api/items")
public class ItemDefsController {

    private final ServerData data;

    public ItemDefsController(ServerData data) {
        this.data = data;
    }

    @GetMapping("/defs")
    public Map<Integer, String> defs() {
        Map<Integer, String> out = new LinkedHashMap<>();
        ItemDefs defs = data.itemDefs();
        if (defs != null) {
            for (int id = 0; id < defs.size(); id++) {
                if (defs.get(id) != null) {
                    out.put(id, defs.nameOf(id));
                }
            }
        }
        return out;
    }

    @GetMapping("/defs-full")
    public Map<Integer, Map<String, Object>> defsFull() {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        ItemDefs defs = data.itemDefs();
        if (defs != null) {
            for (int id = 0; id < defs.size(); id++) {
                ItemDef e = defs.get(id);
                if (e == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", defs.nameOf(id));
                row.put("basePrice", e.basePrice());
                if (e.stackable()) {
                    row.put("stackable", true);
                }
                if (e.untradable()) {
                    row.put("untradeable", true);
                }
                if (e.command() != null && !e.command().isBlank()) {
                    row.put("command", e.command());
                }
                if (e.wearable()) {
                    row.put("wearable", true);
                    if ((e.wearableId() & 24) != 0) {
                        row.put("wield", true);
                    }
                }
                out.put(id, row);
            }
        }
        return out;
    }
}
