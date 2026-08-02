package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import openrsc.gamedata.DataViews;
import openrsc.gamedata.ItemDefs;
import openrsc.gamedata.NpcDefs;
import openrsc.gamedata.NpcLocs;
import openrsc.gamedata.SceneryLocs;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;

/**
 * Bakes the static JSON the viewer fetches from endpoints OUTSIDE
 * {@code /api/world3d/*} — scenery placements + atlas, the NPC spawn table, and
 * the wearables map. The derivations come from {@link DataViews}, the exact same
 * code the live server controllers use, so the baked files are byte-identical to
 * the live endpoints:
 *
 * <ul>
 *   <li>{@code /api/map/scenery.json}       — {@link DataViews#scenery}</li>
 *   <li>{@code /api/map/scenery-atlas.json} + {@code .png} — the sprite atlas</li>
 *   <li>{@code /api/npc-spawns}             — {@link DataViews#spawns}</li>
 *   <li>{@code /api/items/wearables}        — {@link DataViews#wearables}</li>
 * </ul>
 */
public final class AuxDataBaker {

  public static void export(String clientCacheDir, Path siteRoot, Consumer<String> log)
      throws Exception {
    export(clientCacheDir, siteRoot, log, WorldProfile.resolve(ServerConf.resolve()));
  }

  /** As {@link #export(String, Path, Consumer)} for an explicit world. */
  public static void export(String clientCacheDir, Path siteRoot, Consumer<String> log,
      WorldProfile world) throws Exception {
    // The scenery atlas renders sprites, which needs the client defs loaded
    // (idempotent when the world-mesh bake already loaded them this JVM).
    openrsc.bot.render.WorldRenderer.configureCache(clientCacheDir);

    var conf = ServerConf.resolve();

    // Scenery + npc spawns are per-world (WorldProfile mirrors WorldPopulator's file set, order
    // and post-load spawn fixups), so a Cabbage bake carries its Runecraft/Harvesting/CustomQuest
    // content and an authentic bake does not.
    List<SceneryLocs.Loc> scenery = new ArrayList<>();
    for (Path p : world.sceneryLocs(conf)) {
      scenery.addAll(SceneryLocs.load(p));
    }
    List<NpcLocs.Spawn> loadedSpawns = new ArrayList<>();
    for (Path p : world.npcLocs(conf)) {
      loadedSpawns.addAll(NpcLocs.load(p));
    }
    List<NpcLocs.Spawn> spawns = world.applyNpcFixups(loadedSpawns);
    NpcDefs npcDefs = NpcDefs.load(conf.defs().resolve("NpcDefs.json"),
        conf.defs().resolve("NpcDefsCustom.json"));
    ItemDefs itemDefs = ItemDefs.load(conf.defs().resolve("ItemDefs.json"),
        conf.defs().resolve("ItemDefsCustom.json"));

    Path map = siteRoot.resolve("api").resolve("map");
    Path apiRoot = siteRoot.resolve("api");
    Files.createDirectories(map);
    Files.createDirectories(apiRoot.resolve("items"));

    // ---- /api/map/scenery.json ----
    BakeJson.MAPPER.writeValue(map.resolve("scenery.json").toFile(), DataViews.scenery(scenery));
    log.accept("scenery.json: " + scenery.size() + " placements");

    // ---- /api/map/scenery-atlas.{json,png} ----
    SceneryAtlasBaker.bake(clientCacheDir, scenery, map);
    copyIfPresent(SceneryAtlasBaker.atlasJson(map), map.resolve("scenery-atlas.json"), log,
        "scenery-atlas.json");
    copyIfPresent(SceneryAtlasBaker.atlasPng(map), map.resolve("scenery-atlas.png"), log,
        "scenery-atlas.png");

    // ---- /api/npc-spawns ----
    List<DataViews.SpawnDto> spawnDtos = DataViews.spawns(spawns, npcDefs);
    BakeJson.MAPPER.writeValue(apiRoot.resolve("npc-spawns").toFile(), spawnDtos);
    long named = spawnDtos.stream().filter(s -> s.name() != null).count();
    log.accept("npc-spawns: " + spawns.size() + " spawns (" + named + " named)");

    // ---- /api/items/wearables ----
    var wearables = DataViews.wearables(itemDefs);
    BakeJson.MAPPER.writeValue(apiRoot.resolve("items").resolve("wearables").toFile(), wearables);
    log.accept("wearables: " + wearables.size() + " appearance sprites");
  }

  private static void copyIfPresent(Path src, Path dst, Consumer<String> log, String label)
      throws IOException {
    if (src != null && Files.isRegularFile(src)) {
      Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
      log.accept(label + " ok");
    } else {
      log.accept(label + " SKIPPED (atlas not produced)");
    }
  }

  private AuxDataBaker() {}
}
