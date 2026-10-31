package openrsc.maprender.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import openrsc.bot.render.WorldRenderer;
import openrsc.gamedata.DataViews;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.NpcDefs;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.defs.SceneryLocs;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the world data the viewer reads beside the mesh, as {@link DataViews} derives it:
 * {@code scenery.json}, {@code boundaries.json}, {@code npc-spawns.json}, {@code npc-defs.json} and
 * {@code wearables.json} under {@code <siteRoot>/api/world/}, with {@code npc-drops.json} where the
 * world has drop tables, and the scenery atlas's index as
 * {@code <siteRoot>/api/map/scenery-atlas.json}. The atlas image is baked only to make the index and
 * is not written.
 */
public final class AuxDataBaker {

    public static void export(String clientCacheDir, Path siteRoot, Consumer<String> log, WorldProfile world)
            throws Exception {
        WorldRenderer.configureCache(clientCacheDir);

        var conf = ServerConf.resolve();

        List<SceneryLocs.Loc> scenery = new ArrayList<>();
        for (Path p : world.sceneryLocs(conf)) {
            scenery.addAll(SceneryLocs.load(p));
        }
        List<NpcLocs.Spawn> loadedSpawns = new ArrayList<>();
        for (Path p : world.npcLocs(conf)) {
            loadedSpawns.addAll(NpcLocs.load(p));
        }
        List<NpcLocs.Spawn> spawns = world.applyNpcFixups(loadedSpawns);
        List<BoundaryLocs.Loc> boundaries = new ArrayList<>();
        for (Path p : world.boundaryLocs(conf)) {
            boundaries.addAll(BoundaryLocs.load(p));
        }
        NpcDefs npcDefs =
                NpcDefs.load(conf.defs().resolve("NpcDefs.json"), conf.defs().resolve("NpcDefsCustom.json"));
        ItemDefs itemDefs =
                ItemDefs.load(conf.defs().resolve("ItemDefs.json"), conf.defs().resolve("ItemDefsCustom.json"));

        Path api = siteRoot.resolve("api");
        Path worldDir = api.resolve("world");
        Path map = api.resolve("map");
        Files.createDirectories(worldDir);
        Files.createDirectories(map);

        BakeJson.MAPPER.writeValue(worldDir.resolve("scenery.json").toFile(), DataViews.scenery(scenery));
        log.accept("scenery.json: " + scenery.size() + " placements");

        BakeJson.MAPPER.writeValue(worldDir.resolve("boundaries.json").toFile(), DataViews.boundaries(boundaries));
        log.accept("boundaries.json: " + boundaries.size() + " placements");

        List<DataViews.SpawnDto> spawnDtos = DataViews.spawns(spawns, npcDefs);
        BakeJson.MAPPER.writeValue(worldDir.resolve("npc-spawns.json").toFile(), spawnDtos);
        long named = spawnDtos.stream().filter(s -> s.name() != null).count();
        log.accept("npc-spawns.json: " + spawns.size() + " spawns (" + named + " named)");

        List<DataViews.NpcDefDto> defDtos = DataViews.npcDefs(npcDefs, DataViews.npcRespawnScale(conf, world));
        BakeJson.MAPPER.writeValue(worldDir.resolve("npc-defs.json").toFile(), defDtos);
        log.accept("npc-defs.json: " + defDtos.size() + " defs");

        writeDrops(world, itemDefs, worldDir, log);

        var wearables = DataViews.wearables(itemDefs);
        BakeJson.MAPPER.writeValue(worldDir.resolve("wearables.json").toFile(), wearables);
        log.accept("wearables.json: " + wearables.size() + " appearance sprites");

        Path atlasWork = Files.createTempDirectory("scenery-atlas");
        try {
            SceneryAtlasBaker.bake(clientCacheDir, scenery, atlasWork);
            Path index = SceneryAtlasBaker.atlasJson(atlasWork);
            if (Files.isRegularFile(index)) {
                Files.copy(index, map.resolve("scenery-atlas.json"), StandardCopyOption.REPLACE_EXISTING);
                log.accept("scenery-atlas.json ok");
            } else {
                log.accept("scenery-atlas.json SKIPPED (atlas not produced)");
            }
        } finally {
            try (var files = Files.list(atlasWork)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(atlasWork);
        }
    }

    /**
     * Writes {@code npc-drops.json}: the world's drop tables as {@code npc-drops.<world>.json} beside
     * this class holds them, with {@code values}, the base price of every item they name. Nothing is
     * written for a world with no tables.
     */
    static void writeDrops(WorldProfile world, ItemDefs itemDefs, Path worldDir, Consumer<String> log)
            throws IOException {
        String resource = "npc-drops." + world.name() + ".json";
        try (var in = AuxDataBaker.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.accept("npc-drops.json SKIPPED (no " + resource + ")");
                return;
            }
            var drops = (ObjectNode) BakeJson.MAPPER.readTree(in);
            var values = drops.putObject("values");
            Set<Integer> items = new TreeSet<>();
            for (String section : new String[] {"npcs", "tables"}) {
                drops.path(section)
                        .forEach(list -> list.forEach(e -> {
                            if (e.has("item")) {
                                items.add(e.get("item").asInt());
                            }
                        }));
            }
            for (int id : items) {
                var def = id >= 0 && id < itemDefs.size() ? itemDefs.get(id) : null;
                if (def != null) {
                    values.put(String.valueOf(id), def.basePrice());
                }
            }
            BakeJson.MAPPER.writeValue(worldDir.resolve("npc-drops.json").toFile(), drops);
            log.accept("npc-drops.json: " + drops.path("npcs").size() + " npcs, " + items.size() + " items");
        }
    }

    private AuxDataBaker() {}
}
