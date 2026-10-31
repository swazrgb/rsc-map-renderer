package openrsc.gamedata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.BowStringDef;
import openrsc.gamedata.defs.CookingDef;
import openrsc.gamedata.defs.CraftingDef;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.EquipmentTabItems;
import openrsc.gamedata.defs.ExtraDefs;
import openrsc.gamedata.defs.FishingDefs;
import openrsc.gamedata.defs.GemDef;
import openrsc.gamedata.defs.GroundItemLocs;
import openrsc.gamedata.defs.HerbDef;
import openrsc.gamedata.defs.HerbSecondDef;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.LogCutDef;
import openrsc.gamedata.defs.MiningDef;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcDefs;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.ObjectTelePoints;
import openrsc.gamedata.defs.RunecraftDef;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.defs.SmeltingDef;
import openrsc.gamedata.defs.SmithingDef;
import openrsc.gamedata.defs.SpellDef;
import openrsc.gamedata.defs.TileDefs;
import openrsc.gamedata.defs.UnIdentHerbDef;
import openrsc.gamedata.defs.WoodcuttingDef;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's game data for one world: item, NPC, scenery and boundary definitions, the static
 * spawns and locs, the collision map, and the skill tables of {@code defs/extras}. Built by
 * {@link #load} or {@link ServerDataSnapshot#read}, and not changed after, so it may be shared
 * across threads.
 *
 * <p>Each lookup by id answers as the server's {@code EntityHandler} getter for the same table: for
 * an id with no entry, negative and out-of-range ids included, it returns {@code null} unless noted.
 * A lookup is an array read and does not allocate.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L32">EntityHandler</a>
 */
public final class ServerData {

    private static final Logger LOG = LoggerFactory.getLogger(ServerData.class);

    /**
     * A static NPC spawn and its roam box, in tiles; {@code z} is the server's {@code y}.
     *
     * <p>{@code serverIndex} is the spawn's position in load order, which is the index a freshly
     * booted members world gives it: the server registers spawns in that order and each takes the
     * lowest free index. A world that skips spawns at boot (free-to-play, or rats during
     * {@code mice_to_meet_you}) shifts the later indices, which this does not model.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L142">WorldPopulator.populateWorld</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/EntityList.java#L55">EntityList.add</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L113">WorldPopulator.populateWorld</a>
     */
    public record NpcSpawn(int serverIndex, int id, int startX, int startZ, int minX, int minZ, int maxX, int maxZ) {

        /** Whether tile {@code (x, z)} is inside the roam box grown by {@code pad} tiles on every side. */
        public boolean within(int x, int z, int pad) {
            return x >= minX - pad && x <= maxX + pad && z >= minZ - pad && z <= maxZ + pad;
        }
    }

    public record FishingDef(int netId, int baitId, List<FishDef> fish) {}

    public record FishDef(int fishId, int requiredLevel, int exp) {}

    private final ObjectDefs objectDefs;
    private final DoorDefs doorDefs;
    private final List<NpcSpawn> npcSpawns;
    private final NpcDef[] npcById;

    private final ItemDef[] itemById;
    private final SpellDef[] spellById;
    private final int[] healById;

    private final Map<Integer, Integer> edibleHeals;
    private final MiningDef[] miningBySceneryId;
    private final CookingDef[] cookingByRawId;
    private final SmeltingDef[] smeltingByOreId;
    private final FishingDef[][] fishingBySceneryId;

    private final WoodcuttingDef[] woodcuttingBySceneryId;
    private final SmithingDef[] smithingByItemId;

    private final RunecraftDef[] runecraftByAltarId;
    private final CraftingDef[] craftingById;
    private final GemDef[] gemByUncutId;
    private final HerbDef[] herbByHerbId;
    private final HerbSecondDef[][] herbSecondsBySecondId;

    private final UnIdentHerbDef[] unIdentHerbByUnidentId;
    private final LogCutDef[] logCutByLogId;
    private final BowStringDef[] bowStringById;
    private final List<Integer>[] affectedTypesByWieldType;

    private final List<Integer>[] npcServerIndicesById;

    private final CollisionMap collisionMap;
    private final WorldProfile world;
    private final ItemDefs itemDefs;
    private final NpcDefs npcDefs;
    private final TileDefs tileDefs;
    private final List<NpcLocs.Spawn> npcLocs;
    private final List<GroundItemLocs.Spawn> groundItemLocs;
    private final Map<Long, GroundItemLocs.Spawn> groundItemByTile;

    private final List<SceneryLocs.Loc> sceneryLocs;
    private final List<BoundaryLocs.Loc> boundaryLocs;
    private final List<ObjectTelePoints.Point> objectTelePoints;

    /**
     * Loads every definition from {@code conf}, the locs files {@code world} selects, and the collision
     * map built from {@code landscape}. The landscape is not closed here, and not checked against
     * {@code world}.
     *
     * @throws IOException when a definition or locs file cannot be read
     */
    public static ServerData load(ServerConf conf, WorldProfile world, LandscapeSource landscape) throws IOException {
        Instant t0 = Instant.now();
        LOG.info("Loading game data from server conf tree {} for world {}", conf.root(), world);

        ItemDefs itemDefs =
                ItemDefs.load(conf.defs().resolve("ItemDefs.json"), conf.defs().resolve("ItemDefsCustom.json"));
        LOG.info("Loaded {} item defs", itemDefs.size());

        DoorDefs doorDefs = DoorDefs.loadXml(conf.defs().resolve("DoorDef.xml"));
        TileDefs tileDefs = TileDefs.loadXml(conf.defs().resolve("TileDef.xml"));
        ObjectDefs objectDefs = ObjectDefs.loadXml(conf.defs().resolve("GameObjectDef.xml"));

        var sceneryLocs = new ArrayList<SceneryLocs.Loc>();
        for (Path p : world.sceneryLocs(conf)) {
            sceneryLocs.addAll(SceneryLocs.load(p));
        }
        var boundaryLocs = new ArrayList<BoundaryLocs.Loc>();
        for (Path p : world.boundaryLocs(conf)) {
            boundaryLocs.addAll(BoundaryLocs.load(p));
        }

        NpcDefs npcDefs =
                NpcDefs.load(conf.defs().resolve("NpcDefs.json"), conf.defs().resolve("NpcDefsCustom.json"));
        LOG.info("Loaded {} npc defs", npcDefs.size());

        var loadedNpcLocs = new ArrayList<NpcLocs.Spawn>();
        for (Path p : world.npcLocs(conf)) {
            loadedNpcLocs.addAll(NpcLocs.load(p));
        }
        var npcLocs = world.applyNpcFixups(loadedNpcLocs);
        LOG.info("Loaded {} npc spawns", npcLocs.size());

        // A spawn replaces any earlier one on its tile.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L379
        Map<Long, GroundItemLocs.Spawn> byTile = new LinkedHashMap<>();
        for (Path p : world.groundItemLocs(conf)) {
            for (GroundItemLocs.Spawn s : GroundItemLocs.load(p)) {
                long tile = tileKey(s.pos().x(), s.pos().z());
                byTile.remove(tile);
                byTile.put(tile, s);
            }
        }
        LOG.info("Loaded {} ground item spawns", byTile.size());

        Path telePointsXml = conf.extras().resolve("ObjectTelePoints.xml");
        List<ObjectTelePoints.Point> telePoints =
                Files.exists(telePointsXml) ? ObjectTelePoints.load(telePointsXml) : List.of();

        var extraDefs = ExtraDefs.load(conf);
        LOG.info("Loaded skill extras: {}", extraDefs.summary());

        LOG.info("Loading collision from {}", landscape.describe());
        CollisionMap collisionMap =
                new CollisionMap(landscape, doorDefs, tileDefs, sceneryLocs, objectDefs, boundaryLocs);
        LOG.info(
                "Loaded collision map ({}x{}) — {} scenery, {} boundary locs in {}ms",
                collisionMap.width(),
                collisionMap.height(),
                sceneryLocs.size(),
                boundaryLocs.size(),
                Duration.between(t0, Instant.now()).toMillis());

        return new ServerData(
                world,
                itemDefs,
                doorDefs,
                tileDefs,
                objectDefs,
                npcDefs,
                collisionMap,
                List.copyOf(sceneryLocs),
                List.copyOf(boundaryLocs),
                telePoints,
                extraDefs,
                List.copyOf(npcLocs),
                List.copyOf(byTile.values()));
    }

    static ServerData assemble(
            WorldProfile world,
            ItemDefs itemDefs,
            DoorDefs doorDefs,
            TileDefs tileDefs,
            ObjectDefs objectDefs,
            NpcDefs npcDefs,
            CollisionMap collisionMap,
            List<SceneryLocs.Loc> sceneryLocs,
            List<BoundaryLocs.Loc> boundaryLocs,
            List<ObjectTelePoints.Point> objectTelePoints,
            ExtraDefs extraDefs,
            List<NpcLocs.Spawn> npcLocs,
            List<GroundItemLocs.Spawn> groundItemLocs) {
        return new ServerData(
                world,
                itemDefs,
                doorDefs,
                tileDefs,
                objectDefs,
                npcDefs,
                collisionMap,
                List.copyOf(sceneryLocs),
                List.copyOf(boundaryLocs),
                List.copyOf(objectTelePoints),
                extraDefs,
                List.copyOf(npcLocs),
                List.copyOf(groundItemLocs));
    }

    @SuppressWarnings("unchecked")
    private ServerData(
            WorldProfile world,
            ItemDefs itemDefs,
            DoorDefs doorDefs,
            TileDefs tileDefs,
            ObjectDefs objectDefs,
            NpcDefs npcDefs,
            CollisionMap collisionMap,
            List<SceneryLocs.Loc> sceneryLocs,
            List<BoundaryLocs.Loc> boundaryLocs,
            List<ObjectTelePoints.Point> objectTelePoints,
            ExtraDefs defs,
            List<NpcLocs.Spawn> npcLocs,
            List<GroundItemLocs.Spawn> groundItemLocs) {
        // An equipment-tab server makes some items wearable as it loads them; applying this to defs
        // already rewritten changes nothing, so a snapshot reads back the same.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L462
        itemDefs = world.rules().equipmentTab() ? EquipmentTabItems.apply(itemDefs) : itemDefs;
        this.itemDefs = itemDefs;
        this.npcDefs = npcDefs;
        this.tileDefs = tileDefs;
        this.npcLocs = npcLocs;
        this.groundItemLocs = groundItemLocs;
        Map<Long, GroundItemLocs.Spawn> byTile = new HashMap<>();
        for (GroundItemLocs.Spawn s : groundItemLocs) {
            byTile.put(tileKey(s.pos().x(), s.pos().z()), s);
        }
        this.groundItemByTile = Map.copyOf(byTile);
        this.sceneryLocs = sceneryLocs;
        this.boundaryLocs = boundaryLocs;
        this.objectTelePoints = objectTelePoints;
        this.world = Objects.requireNonNull(world, "world");
        Instant t0 = Instant.now();
        this.objectDefs = objectDefs;
        this.doorDefs = doorDefs;
        this.collisionMap = collisionMap;
        List<SmithingDef> sm = new ArrayList<>(defs.smithing().size());
        int maxSmithItem = -1;
        for (SmithingDef d : defs.smithing().defs()) {
            sm.add(d);
            maxSmithItem = Math.max(maxSmithItem, d.itemId());
        }
        SmithingDef[] smById = new SmithingDef[maxSmithItem + 1];
        for (SmithingDef s : sm) {
            if (s.itemId() >= 0 && smById[s.itemId()] == null) {
                smById[s.itemId()] = s;
            }
        }
        this.smithingByItemId = smById;

        List<NpcSpawn> ns = new ArrayList<>(npcLocs.size());
        int maxSpawnId = -1;
        for (int i = 0; i < npcLocs.size(); i++) {
            NpcLocs.Spawn s = npcLocs.get(i);
            ns.add(new NpcSpawn(
                    i,
                    s.id(),
                    s.start().x(),
                    s.start().z(),
                    s.min().x(),
                    s.min().z(),
                    s.max().x(),
                    s.max().z()));
            maxSpawnId = Math.max(maxSpawnId, s.id());
        }
        this.npcSpawns = List.copyOf(ns);
        List<Integer>[] idxById = new List[maxSpawnId + 1];
        {
            List<List<Integer>> acc = new ArrayList<>(idxById.length);
            for (int i = 0; i < idxById.length; i++) {
                acc.add(null);
            }
            for (NpcSpawn s : this.npcSpawns) {
                if (s.id() < 0) {
                    continue;
                }
                List<Integer> l = acc.get(s.id());
                if (l == null) {
                    l = new ArrayList<>();
                    acc.set(s.id(), l);
                }
                l.add(s.serverIndex());
            }
            for (int i = 0; i < idxById.length; i++) {
                List<Integer> l = acc.get(i);
                idxById[i] = l == null ? List.of() : List.copyOf(l);
            }
        }
        this.npcServerIndicesById = idxById;

        NpcDef[] npcArr = new NpcDef[npcDefs.size()];
        for (int id = 0; id < npcArr.length; id++) {
            npcArr[id] = npcDefs.get(id);
        }
        this.npcById = npcArr;

        ItemDef[] itemArr = new ItemDef[itemDefs.size()];
        for (int id = 0; id < itemArr.length; id++) {
            itemArr[id] = itemDefs.get(id);
        }
        this.itemById = itemArr;

        SpellDef[] spells = new SpellDef[defs.spells().size()];
        for (int i = 0; i < spells.length; i++) {
            var d = defs.spells().get(i);
            spells[i] = new SpellDef(d.id(), d.name(), d.requiredLevel());
        }
        this.spellById = spells;

        this.edibleHeals = defs.edibleHeals().byItemId();
        int[] heal = new int[maxKey(this.edibleHeals) + 1];
        for (var e : this.edibleHeals.entrySet()) {
            if (e.getKey() >= 0) {
                heal[e.getKey()] = e.getValue();
            }
        }
        this.healById = heal;

        this.miningBySceneryId = project(defs.mining().bySceneryId(), MiningDef[]::new, Function.identity());
        this.cookingByRawId = project(defs.cooking().byRawItemId(), CookingDef[]::new, Function.identity());
        this.smeltingByOreId = project(defs.smelting().byOreId(), SmeltingDef[]::new, Function.identity());
        this.fishingBySceneryId = project(defs.fishing().bySceneryId(), FishingDef[][]::new, spots -> {
            FishingDef[] out = new FishingDef[spots.size()];
            for (int i = 0; i < out.length; i++) {
                FishingDefs.Spot s = spots.get(i);
                List<FishDef> fish = new ArrayList<>(s.fish().size());
                for (FishingDefs.Fish f : s.fish()) {
                    fish.add(new FishDef(f.fishId(), f.requiredLevel(), f.exp()));
                }
                out[i] = new FishingDef(s.netId(), s.baitId(), List.copyOf(fish));
            }
            return out;
        });
        this.woodcuttingBySceneryId =
                project(defs.woodcutting().bySceneryId(), WoodcuttingDef[]::new, Function.identity());
        this.runecraftByAltarId = project(defs.runecraft().byAltarId(), RunecraftDef[]::new, Function.identity());
        this.craftingById = project(defs.crafting().byId(), CraftingDef[]::new, Function.identity());
        this.gemByUncutId = project(defs.gems().byUncutId(), GemDef[]::new, Function.identity());
        this.herbByHerbId = project(defs.herbs().byHerbId(), HerbDef[]::new, Function.identity());
        this.unIdentHerbByUnidentId =
                project(defs.unidentHerbs().byUnidentId(), UnIdentHerbDef[]::new, Function.identity());
        this.logCutByLogId = project(defs.logCut().byLogId(), LogCutDef[]::new, Function.identity());
        this.bowStringById = project(defs.bowStrings().byUnstrungId(), BowStringDef[]::new, Function.identity());
        List<Integer>[] at = project(defs.affectedTypes().byType(), List[]::new, Function.identity());
        for (int i = 0; i < at.length; i++) {
            if (at[i] == null) {
                at[i] = List.of();
            }
        }
        this.affectedTypesByWieldType = at;

        int maxSecond = -1;
        for (HerbSecondDef d : defs.herbSeconds().defs()) {
            maxSecond = Math.max(maxSecond, d.secondId());
        }
        List<List<HerbSecondDef>> grouped = new ArrayList<>(maxSecond + 1);
        for (int i = 0; i <= maxSecond; i++) {
            grouped.add(null);
        }
        for (HerbSecondDef d : defs.herbSeconds().defs()) {
            if (d.secondId() < 0) {
                continue;
            }
            List<HerbSecondDef> l = grouped.get(d.secondId());
            if (l == null) {
                l = new ArrayList<>(2);
                grouped.set(d.secondId(), l);
            }
            l.add(new HerbSecondDef(d.secondId(), d.unfinishedId(), d.potionId(), d.requiredLevel(), d.exp()));
        }
        HerbSecondDef[][] hs = new HerbSecondDef[maxSecond + 1][];
        for (int i = 0; i <= maxSecond; i++) {
            List<HerbSecondDef> l = grouped.get(i);
            hs[i] = l == null ? null : l.toArray(new HerbSecondDef[0]);
        }
        this.herbSecondsBySecondId = hs;
        LOG.info(
                "Projected server data ({} items, {} npcs, {} spawns, {} spells) in {}ms",
                itemById.length,
                npcById.length,
                this.npcSpawns.size(),
                spellById.length,
                Duration.between(t0, Instant.now()).toMillis());
    }

    public ItemDef item(int itemId) {
        return at(itemById, itemId);
    }

    public boolean isStackable(int itemId) {
        ItemDef def = item(itemId);
        return def != null && def.stackable();
    }

    /**
     * Whether any quantity of {@code itemId} takes one inventory slot when withdrawn: a stackable, or,
     * on a world with bank notes, an item the bank can hand out as notes. False for an unknown id.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L476">Bank.withdrawItemToInventory</a>
     */
    public boolean ridesAsStack(int itemId) {
        ItemDef def = item(itemId);
        return def != null && (def.stackable() || (world().rules().bankNotes() && def.bankNoteable()));
    }

    /** Whether {@code itemId} is withdrawn as notes: it {@link #ridesAsStack} but does not stack itself. */
    public boolean ridesAsNotes(int itemId) {
        ItemDef def = item(itemId);
        return def != null && !def.stackable() && world().rules().bankNotes() && def.bankNoteable();
    }

    public NpcDef npc(int npcId) {
        return at(npcById, npcId);
    }

    public ObjectDef object(int sceneryId) {
        return objectDefs.get(sceneryId);
    }

    /** See {@link DoorDefs#isGenericFreeDoor}. */
    public boolean isGenericFreeDoor(int boundaryId) {
        return doorDefs.isGenericFreeDoor(boundaryId);
    }

    /** See {@link ObjectDefs#isGenericFreeDoor}. */
    public boolean isGenericFreeSceneryDoor(int sceneryId) {
        return objectDefs.isGenericFreeDoor(sceneryId);
    }

    public CollisionMap collisionMap() {
        return collisionMap;
    }

    public WorldProfile world() {
        return world;
    }

    public ItemDefs itemDefs() {
        return itemDefs;
    }

    public DoorDefs doorDefs() {
        return doorDefs;
    }

    public TileDefs tileDefs() {
        return tileDefs;
    }

    public ObjectDefs objectDefs() {
        return objectDefs;
    }

    public NpcDefs npcDefs() {
        return npcDefs;
    }

    /** The NPC spawns in load order, after {@link WorldProfile#applyNpcFixups}. */
    public List<NpcLocs.Spawn> npcLocs() {
        return npcLocs;
    }

    /** The ground items the server spawns at start-up, at most one per tile. */
    public List<GroundItemLocs.Spawn> groundItemLocs() {
        return groundItemLocs;
    }

    /** Whether the server spawns item {@code itemId} on tile ({@code x}, {@code z}). */
    public boolean isGroundItemSpawn(int itemId, int x, int z) {
        GroundItemLocs.Spawn s = groundItemByTile.get(tileKey(x, z));
        return s != null && s.id() == itemId;
    }

    private static long tileKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public List<SceneryLocs.Loc> sceneryLocs() {
        return sceneryLocs;
    }

    public List<BoundaryLocs.Loc> boundaryLocs() {
        return boundaryLocs;
    }

    /** Empty when the conf tree ships no {@code extras/ObjectTelePoints.xml}. */
    public List<ObjectTelePoints.Point> objectTelePoints() {
        return objectTelePoints;
    }

    /** In load order, so a spawn's position in the list is its {@link NpcSpawn#serverIndex}. */
    public List<NpcSpawn> npcSpawns() {
        return npcSpawns;
    }

    public NpcSpawn npcSpawnByServerIndex(int serverIndex) {
        return serverIndex >= 0 && serverIndex < npcSpawns.size() ? npcSpawns.get(serverIndex) : null;
    }

    /**
     * The static spawn of a live NPC: the one at {@code serverIndex} when it is NPC {@code npcId} and its roam box
     * holds {@code (x, z)}, else the spawn of {@code npcId} whose roam box holds it, nearest in index, else the one
     * at {@code serverIndex} if it is that NPC. The server registers spawns in load order, so a switch this world's
     * conf has off that keeps or skips spawns at boot, such as a seasonal event, moves every later index. Null when
     * no spawn fits.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L142">WorldPopulator.populateWorld</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L133">WorldPopulator.populateWorld, rats skipped</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/database/WorldPopulator.java#L283">WorldPopulator.loadCustomLocs, event spawns kept</a>
     */
    public @Nullable NpcSpawn npcSpawnOf(int serverIndex, int npcId, int x, int z) {
        NpcSpawn at = npcSpawnByServerIndex(serverIndex);
        boolean same = at != null && at.id() == npcId;
        if (same && at.within(x, z, 0)) {
            return at;
        }
        NpcSpawn best = null;
        for (int i : npcServerIndicesOf(npcId)) {
            NpcSpawn s = npcSpawns.get(i);
            if (s.within(x, z, 0)
                    && (best == null || Math.abs(i - serverIndex) < Math.abs(best.serverIndex() - serverIndex))) {
                best = s;
            }
        }
        return best != null ? best : same ? at : null;
    }

    /** The serverIndex of every static spawn of NPC {@code npcId}, ascending; empty when it has none. */
    public List<Integer> npcServerIndicesOf(int npcId) {
        List<Integer> l = at(npcServerIndicesById, npcId);
        return l == null ? List.of() : l;
    }

    /**
     * The spell at position {@code spellId} of {@code SpellDef.xml}, the id the server looks spells up
     * by.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L1027">EntityHandler.getSpellDef</a>
     */
    public SpellDef spell(int spellId) {
        return at(spellById, spellId);
    }

    /**
     * Hits healed by eating {@code itemId}; 0 when it is not edible.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L845">EntityHandler.getItemEdibleHeals</a>
     */
    public int healFor(int itemId) {
        return (itemId >= 0 && itemId < healById.length) ? healById[itemId] : 0;
    }

    /** Item id to hits healed, for every edible item. */
    public Map<Integer, Integer> edibleHeals() {
        return edibleHeals;
    }

    public MiningDef mining(int sceneryId) {
        return at(miningBySceneryId, sceneryId);
    }

    public CookingDef cooking(int rawItemId) {
        return at(cookingByRawId, rawItemId);
    }

    public SmeltingDef smelting(int oreId) {
        return at(smeltingByOreId, oreId);
    }

    /**
     * The table behind one command of a fishing spot: {@code commandIndex} 0 for its command1, 1 for
     * its command2. {@code null} when the scenery is no spot or has no table at that index.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L940">EntityHandler.getObjectFishingDef</a>
     */
    public FishingDef fishing(int spotSceneryId, int commandIndex) {
        FishingDef[] spots = at(fishingBySceneryId, spotSceneryId);
        return spots == null ? null : at(spots, commandIndex);
    }

    public WoodcuttingDef woodcutting(int sceneryId) {
        return at(woodcuttingBySceneryId, sceneryId);
    }

    /**
     * The first smithable product in the server's table that produces {@code itemId}, as the server's
     * scan finds it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L1014">EntityHandler.getSmithingDefbyID</a>
     */
    public SmithingDef smithing(int itemId) {
        return at(smithingByItemId, itemId);
    }

    public RunecraftDef runecraft(int altarSceneryId) {
        return at(runecraftByAltarId, altarSceneryId);
    }

    public CraftingDef crafting(int id) {
        return at(craftingById, id);
    }

    public GemDef gem(int uncutId) {
        return at(gemByUncutId, uncutId);
    }

    public HerbDef herb(int herbId) {
        return at(herbByHerbId, herbId);
    }

    /**
     * The entry that turns unfinished potion {@code unfinishedId} into a potion with secondary
     * {@code secondId}, or {@code null}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L871">EntityHandler.getItemHerbSecond</a>
     */
    public HerbSecondDef herbSecond(int secondId, int unfinishedId) {
        HerbSecondDef[] candidates = at(herbSecondsBySecondId, secondId);
        if (candidates == null) {
            return null;
        }
        for (HerbSecondDef h : candidates) {
            if (h.unfinishedId() == unfinishedId) {
                return h;
            }
        }
        return null;
    }

    public UnIdentHerbDef unIdentHerb(int unidentId) {
        return at(unIdentHerbByUnidentId, unidentId);
    }

    public LogCutDef logCut(int logId) {
        return at(logCutByLogId, logId);
    }

    public BowStringDef bowString(int id) {
        return at(bowStringById, id);
    }

    /**
     * The wear types that wielding an item of wear type {@code wieldType} takes off; empty for an
     * unknown type.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L780">EntityHandler.getAffectedTypes</a>
     */
    public List<Integer> affectedTypes(int wieldType) {
        List<Integer> l = at(affectedTypesByWieldType, wieldType);
        return l == null ? List.of() : l;
    }

    /**
     * Whether wielding {@code newItemId} takes worn {@code wornItemId} off, decided as the server does
     * for one pair: the incoming item's affected types name the worn item's wear type. False for the
     * same id, and when either id is unknown or not wearable.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Item.java#L243">Item.wieldingAffectsItem</a>
     */
    public boolean displaces(int newItemId, int wornItemId) {
        if (newItemId == wornItemId) {
            return false;
        }
        ItemDef incoming = item(newItemId);
        ItemDef worn = item(wornItemId);
        if (incoming == null || worn == null || !incoming.wearable() || !worn.wearable()) {
            return false;
        }
        return affectedTypes(incoming.wearableId()).contains(worn.wearableId());
    }

    private static <D, R> R[] project(Map<Integer, D> src, IntFunction<R[]> newArr, Function<D, R> f) {
        R[] out = newArr.apply(maxKey(src) + 1);
        for (var e : src.entrySet()) {
            if (e.getKey() >= 0) {
                out[e.getKey()] = f.apply(e.getValue());
            }
        }
        return out;
    }

    private static int maxKey(Map<Integer, ?> m) {
        int max = -1;
        for (Integer k : m.keySet()) {
            max = Math.max(max, k);
        }
        return max;
    }

    private static <T> T at(T[] arr, int i) {
        return (i >= 0 && i < arr.length) ? arr[i] : null;
    }
}
