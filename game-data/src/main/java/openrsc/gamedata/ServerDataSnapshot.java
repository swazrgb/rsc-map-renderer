package openrsc.gamedata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.ExtraDefs;
import openrsc.gamedata.defs.GroundItemLocs;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcDefs;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.ObjectTelePoints;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.defs.TileDefs;
import openrsc.gamedata.defs.xml.XmlTree;
import openrsc.gamedata.world.CollisionMap;

/**
 * A {@link ServerData} as one byte array, readable without the conf tree, the landscape or an XML
 * parser.
 *
 * <p>The JSON-sourced parts (item and NPC defs, the scenery, boundary and NPC locs) and the built
 * collision planes are stored as values. The XML-sourced parts (door, tile and object defs, the
 * skill tables, the spells and the teleport points) are stored as parsed element trees and read
 * back through the same loaders {@link ServerData#load} uses, so both loads build the same tables.
 *
 * <p>The layout is big-endian: magic, version, then the sections in {@link #read}'s order. A string
 * is its UTF-8 length ({@code -1} for {@code null}) and bytes.
 */
public final class ServerDataSnapshot {

    private static final int MAGIC = 0x52534344;
    /** Raised with every change to the layout; {@link #read} refuses any other version. */
    private static final int VERSION = 10;

    private static final List<String> DEFS_XML =
            List.of("DoorDef.xml", "TileDef.xml", "GameObjectDef.xml", ExtraDefs.SPELL_FILE);

    private static final String TELEPORTS = "ObjectTelePoints.xml";

    private ServerDataSnapshot() {}

    /**
     * Writes {@code data}, which must have been loaded from {@code conf}: the XML parts are parsed
     * again from that tree.
     *
     * @throws IOException when an XML file of {@code conf} cannot be read
     */
    public static byte[] write(ServerConf conf, ServerData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 24);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);

        WorldProfile world = data.world();
        writeString(out, world.name());
        writeString(out, world.dbName());
        out.writeInt(world.basedMapData());
        out.writeInt(world.locationData());
        out.writeBoolean(world.memberWorld());
        out.writeInt(world.viewDistance());
        out.writeInt(world.features().size());
        for (WorldProfile.Feature f : world.features()) {
            writeString(out, f.name());
        }
        writeRules(out, world.rules());

        Map<String, XmlTree> docs = new LinkedHashMap<>();
        for (String name : DEFS_XML) {
            docs.put(name, XmlTree.parse(conf.defs().resolve(name)));
        }
        for (String name : ExtraDefs.EXTRAS_FILES) {
            docs.put("extras/" + name, XmlTree.parse(conf.extras().resolve(name)));
        }
        Path teleports = conf.extras().resolve(TELEPORTS);
        if (Files.exists(teleports)) {
            docs.put("extras/" + TELEPORTS, XmlTree.parse(teleports));
        }
        Map<String, Integer> names = new LinkedHashMap<>();
        for (XmlTree doc : docs.values()) {
            nameElements(doc, names);
        }
        out.writeInt(names.size());
        for (String name : names.keySet()) {
            writeString(out, name);
        }
        out.writeInt(docs.size());
        for (Map.Entry<String, XmlTree> e : docs.entrySet()) {
            writeString(out, e.getKey());
            writeTree(out, e.getValue(), names);
        }

        ItemDefs items = data.itemDefs();
        out.writeInt(items.size());
        for (int id = 0; id < items.size(); id++) {
            ItemDef d = items.get(id);
            out.writeBoolean(d != null);
            if (d != null) {
                out.writeInt(d.id());
                writeString(out, d.name());
                writeString(out, d.description());
                out.writeInt(d.basePrice());
                out.writeBoolean(d.stackable());
                out.writeBoolean(d.wearable());
                out.writeInt(d.requiredLevel());
                out.writeInt(d.requiredSkillId());
                out.writeInt(d.wearSlot());
                out.writeInt(d.wearableId());
                out.writeBoolean(d.untradable());
                out.writeBoolean(d.noteable());
                out.writeBoolean(d.membersOnly());
                out.writeBoolean(d.femaleOnly());
                writeString(out, d.command());
                out.writeInt(d.appearanceId());
                out.writeInt(d.armourBonus());
                out.writeInt(d.weaponAimBonus());
                out.writeInt(d.weaponPowerBonus());
                out.writeInt(d.magicBonus());
                out.writeInt(d.prayerBonus());
            }
        }

        NpcDefs npcs = data.npcDefs();
        out.writeInt(npcs.size());
        for (int id = 0; id < npcs.size(); id++) {
            NpcDef d = npcs.get(id);
            out.writeBoolean(d != null);
            if (d != null) {
                out.writeInt(d.id());
                writeString(out, d.name());
                out.writeInt(d.combatLevel());
                out.writeInt(d.hits());
                out.writeBoolean(d.attackable());
                out.writeBoolean(d.aggressive());
                out.writeInt(d.respawnTime());
                writeString(out, d.description());
                out.writeInt(d.attack());
                out.writeInt(d.strength());
                out.writeInt(d.defense());
                out.writeBoolean(d.ranged());
                out.writeBoolean(d.members());
            }
        }

        out.writeInt(data.sceneryLocs().size());
        for (SceneryLocs.Loc l : data.sceneryLocs()) {
            out.writeInt(l.id());
            out.writeInt(l.pos().x());
            out.writeInt(l.pos().y());
            out.writeInt(l.direction());
        }
        out.writeInt(data.boundaryLocs().size());
        for (BoundaryLocs.Loc l : data.boundaryLocs()) {
            out.writeInt(l.id());
            out.writeInt(l.pos().x());
            out.writeInt(l.pos().y());
            out.writeInt(l.direction());
        }
        out.writeInt(data.npcLocs().size());
        for (NpcLocs.Spawn s : data.npcLocs()) {
            out.writeInt(s.id());
            out.writeInt(s.start().x());
            out.writeInt(s.start().z());
            out.writeInt(s.min().x());
            out.writeInt(s.min().z());
            out.writeInt(s.max().x());
            out.writeInt(s.max().z());
        }
        out.writeInt(data.groundItemLocs().size());
        for (GroundItemLocs.Spawn s : data.groundItemLocs()) {
            out.writeInt(s.id());
            out.writeInt(s.pos().x());
            out.writeInt(s.pos().z());
            out.writeInt(s.amount());
            out.writeInt(s.respawn());
        }

        CollisionMap.Planes p = data.collisionMap().planes();
        out.writeInt(p.componentCount());
        out.write(p.flags());
        out.write(p.objectFlags());
        for (boolean b : p.projectileAllowed()) {
            out.writeBoolean(b);
        }
        for (short v : p.components()) {
            out.writeShort(v);
        }
        out.write(p.staticSteps());
        out.write(p.groundTexture());
        out.write(p.groundOverlay());
        out.write(p.roofTexture());
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * Rebuilds the world {@code snapshot} holds, reporting its stages to {@link LoadProgress}.
     *
     * @throws IllegalArgumentException when {@code snapshot} is not one, has another version, or has
     *     bytes left over
     */
    public static ServerData read(byte[] snapshot) {
        Reader in = new Reader(snapshot);
        if (in.i32() != MAGIC) {
            throw new IllegalArgumentException("not a ServerData snapshot");
        }
        int version = in.i32();
        if (version != VERSION) {
            throw new IllegalArgumentException("snapshot version " + version + ", this reads " + VERSION);
        }

        WorldProfile.Builder wb = WorldProfile.builder(in.string())
                .dbName(in.string())
                .basedMapData(in.i32())
                .locationData(in.i32())
                .memberWorld(in.bool())
                .viewDistance(in.i32());
        int featureCount = in.i32();
        Set<WorldProfile.Feature> features = EnumSet.noneOf(WorldProfile.Feature.class);
        for (int i = 0; i < featureCount; i++) {
            features.add(WorldProfile.Feature.valueOf(in.string()));
        }
        for (WorldProfile.Feature f : features) {
            wb.enable(f);
        }
        WorldProfile world = wb.rules(readRules(in)).build();

        LoadProgress.stage("reading item, npc and spawn tables");
        String[] elementNames = new String[in.i32()];
        for (int i = 0; i < elementNames.length; i++) {
            elementNames[i] = in.string();
        }
        Map<String, XmlTree> docs = new HashMap<>();
        int docCount = in.i32();
        for (int i = 0; i < docCount; i++) {
            docs.put(in.string(), readTree(in, elementNames));
        }

        ItemDef[] items = new ItemDef[in.i32()];
        for (int id = 0; id < items.length; id++) {
            if (in.bool()) {
                items[id] = new ItemDef(
                        in.i32(),
                        in.string(),
                        in.string(),
                        in.i32(),
                        in.bool(),
                        in.bool(),
                        in.i32(),
                        in.i32(),
                        in.i32(),
                        in.i32(),
                        in.bool(),
                        in.bool(),
                        in.bool(),
                        in.bool(),
                        in.string(),
                        in.i32(),
                        in.i32(),
                        in.i32(),
                        in.i32(),
                        in.i32(),
                        in.i32());
            }
        }
        NpcDef[] npcs = new NpcDef[in.i32()];
        for (int id = 0; id < npcs.length; id++) {
            if (in.bool()) {
                npcs[id] = new NpcDef(
                        in.i32(),
                        in.string(),
                        in.i32(),
                        in.i32(),
                        in.bool(),
                        in.bool(),
                        in.i32(),
                        in.string(),
                        in.i32(),
                        in.i32(),
                        in.i32(),
                        in.bool(),
                        in.bool());
            }
        }

        List<SceneryLocs.Loc> scenery = new ArrayList<>();
        for (int n = in.i32(), i = 0; i < n; i++) {
            scenery.add(new SceneryLocs.Loc(in.i32(), new SceneryLocs.Pos(in.i32(), in.i32()), in.i32()));
        }
        List<BoundaryLocs.Loc> boundaries = new ArrayList<>();
        for (int n = in.i32(), i = 0; i < n; i++) {
            boundaries.add(new BoundaryLocs.Loc(in.i32(), new BoundaryLocs.Pos(in.i32(), in.i32()), in.i32()));
        }
        List<NpcLocs.Spawn> npcLocs = new ArrayList<>();
        for (int n = in.i32(), i = 0; i < n; i++) {
            npcLocs.add(new NpcLocs.Spawn(
                    in.i32(),
                    new NpcLocs.Pos(in.i32(), in.i32()),
                    new NpcLocs.Pos(in.i32(), in.i32()),
                    new NpcLocs.Pos(in.i32(), in.i32())));
        }
        List<GroundItemLocs.Spawn> groundItemLocs = new ArrayList<>();
        for (int n = in.i32(), i = 0; i < n; i++) {
            groundItemLocs.add(
                    new GroundItemLocs.Spawn(in.i32(), new GroundItemLocs.Pos(in.i32(), in.i32()), in.i32(), in.i32()));
        }

        LoadProgress.stage("reading the collision map");
        int tiles = CollisionMap.WIDTH * CollisionMap.HEIGHT;
        int componentCount = in.i32();
        byte[] flags = in.bytes(tiles);
        byte[] objectFlags = in.bytes(tiles);
        boolean[] projectileAllowed = new boolean[tiles];
        for (int i = 0; i < tiles; i++) {
            projectileAllowed[i] = in.bool();
        }
        short[] components = new short[tiles];
        for (int i = 0; i < tiles; i++) {
            components[i] = in.i16();
        }
        CollisionMap.Planes planes = new CollisionMap.Planes(
                flags,
                objectFlags,
                projectileAllowed,
                components,
                componentCount,
                in.bytes(tiles),
                in.bytes(tiles),
                in.bytes(tiles),
                in.bytes(tiles));
        if (!in.done()) {
            throw new IllegalArgumentException("trailing bytes after the collision planes");
        }

        LoadProgress.stage("parsing door, tile, object and spell definitions");
        DoorDefs doors = DoorDefs.fromXml(tree(docs, "DoorDef.xml"));
        TileDefs tileDefs = TileDefs.fromXml(tree(docs, "TileDef.xml"));
        ObjectDefs objects = ObjectDefs.fromXml(tree(docs, "GameObjectDef.xml"));
        ExtraDefs extras = ExtraDefs.from(name -> tree(docs, "extras/" + name), tree(docs, ExtraDefs.SPELL_FILE));
        XmlTree teleports = docs.get("extras/" + TELEPORTS);
        List<ObjectTelePoints.Point> telePoints = teleports == null ? List.of() : ObjectTelePoints.from(teleports);
        LoadProgress.stage("indexing scenery and boundaries");
        CollisionMap map = CollisionMap.restore(planes, scenery, objects, boundaries, doors);
        return ServerData.assemble(
                world,
                ItemDefs.of(items),
                doors,
                tileDefs,
                objects,
                NpcDefs.of(npcs),
                map,
                scenery,
                boundaries,
                telePoints,
                extras,
                npcLocs,
                groundItemLocs);
    }

    private static void writeRules(DataOutputStream out, ServerRules r) throws IOException {
        writeString(out, r.npcBlocking().name());
        out.writeInt(r.maxPvpMeleeDistance());
        out.writeBoolean(r.fatigue());
        out.writeBoolean(r.sleeping());
        out.writeBoolean(r.equipmentTab());
        out.writeBoolean(r.batching());
        out.writeInt(r.bankCapacity());
        out.writeBoolean(r.customBanks());
        out.writeBoolean(r.quickBank());
        out.writeBoolean(r.bankPins());
        out.writeBoolean(r.bankNotes());
        out.writeBoolean(r.bankPresets());
        out.writeBoolean(r.certDeposit());
        out.writeBoolean(r.certerBankExchange());
        out.writeBoolean(r.dropX());
        out.writeBoolean(r.quickTrade());
        out.writeBoolean(r.decanting());
        out.writeBoolean(r.postQuestRegionsOpen());
        out.writeBoolean(r.postQuestItemsReclaimable());
        out.writeBoolean(r.wormbrainBarter());
        out.writeInt(r.gameTickMs());
        out.writeDouble(r.npcRespawnMultiplier());
        out.writeInt(r.maxItemId());
        out.writeInt(r.maxSceneryId());
        out.writeDouble(r.combatExpRate());
        out.writeDouble(r.skillingExpRate());
        out.writeBoolean(r.fishingSpotsDeplete());
        out.writeBoolean(r.extendedMiningRocks());
        out.writeBoolean(r.npcsDontRetreat());
        out.writeBoolean(r.leftClickWebs());
        out.writeBoolean(r.noLevelRequirementWield());
        out.writeBoolean(r.equipQuestItemsWithoutQuests());
        out.writeBoolean(r.strictCheckAll());
        out.writeBoolean(r.strictPoisonedDartCheck());
        out.writeBoolean(r.strictPoisonedKnifeCheck());
        out.writeBoolean(r.strictPoisonedSpearCheck());
        out.writeBoolean(r.formFittingChainmail());
        out.writeBoolean(r.combatOdyssey());
        out.writeBoolean(r.kbdRareDropTable());
    }

    private static ServerRules readRules(Reader in) {
        return new ServerRules(
                ServerRules.NpcBlocking.valueOf(in.string()),
                in.i32(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.i32(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.i32(),
                in.f64(),
                in.i32(),
                in.i32(),
                in.f64(),
                in.f64(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool(),
                in.bool());
    }

    private static XmlTree tree(Map<String, XmlTree> docs, String name) {
        XmlTree doc = docs.get(name);
        if (doc == null) {
            throw new IllegalArgumentException("snapshot has no " + name);
        }
        return doc;
    }

    private static void nameElements(XmlTree node, Map<String, Integer> names) {
        names.putIfAbsent(node.name, names.size());
        for (XmlTree child : node.children()) {
            nameElements(child, names);
        }
    }

    private static void writeTree(DataOutputStream out, XmlTree node, Map<String, Integer> names) throws IOException {
        out.writeInt(names.get(node.name));
        writeString(out, node.text());
        out.writeInt(node.children().size());
        for (XmlTree child : node.children()) {
            writeTree(out, child, names);
        }
    }

    private static XmlTree readTree(Reader in, String[] names) {
        String name = names[in.i32()];
        String text = in.string();
        int n = in.i32();
        List<XmlTree> children = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            children.add(readTree(in, names));
        }
        return XmlTree.of(name, text, children);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static final class Reader {
        private final byte[] b;
        private int at;

        Reader(byte[] b) {
            this.b = b;
        }

        int i32() {
            int v = ((b[at] & 0xFF) << 24)
                    | ((b[at + 1] & 0xFF) << 16)
                    | ((b[at + 2] & 0xFF) << 8)
                    | (b[at + 3] & 0xFF);
            at += 4;
            return v;
        }

        double f64() {
            long hi = i32() & 0xFFFFFFFFL;
            long lo = i32() & 0xFFFFFFFFL;
            return Double.longBitsToDouble((hi << 32) | lo);
        }

        short i16() {
            short v = (short) (((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF));
            at += 2;
            return v;
        }

        boolean bool() {
            return b[at++] != 0;
        }

        byte[] bytes(int n) {
            byte[] out = new byte[n];
            System.arraycopy(b, at, out, 0, n);
            at += n;
            return out;
        }

        String string() {
            int n = i32();
            if (n < 0) {
                return null;
            }
            String s = new String(b, at, n, StandardCharsets.UTF_8);
            at += n;
            return s;
        }

        boolean done() {
            return at == b.length;
        }
    }
}
