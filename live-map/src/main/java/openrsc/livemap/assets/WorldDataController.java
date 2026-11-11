package openrsc.livemap.assets;

import java.util.List;
import java.util.Map;
import openrsc.gamedata.DataViews;
import openrsc.gamedata.ServerData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the world's static data the viewer draws: scenery, boundaries, NPC spawns, and the wearable items by appearance sprite. */
@RestController
@RequestMapping("/api/world")
public class WorldDataController {

    private final ServerData env;

    public WorldDataController(ServerData env) {
        this.env = env;
    }

    @GetMapping("/scenery.json")
    public List<DataViews.SceneryDto> scenery() {
        return DataViews.scenery(env.sceneryLocs());
    }

    @GetMapping("/boundaries.json")
    public DataViews.BoundariesDto boundaries() {
        return DataViews.boundaries(env.boundaryLocs());
    }

    @GetMapping("/npc-spawns.json")
    public List<DataViews.SpawnDto> spawns() {
        return DataViews.spawns(env.npcLocs(), env.npcDefs());
    }

    @GetMapping("/wearables.json")
    public Map<Integer, List<String>> wearables() {
        return DataViews.wearables(env.itemDefs());
    }
}
