package openrsc.maprender.bake;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.openrsc.client.entityhandling.EntityHandler;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import openrsc.bot.render.ObjectSpriteRenderer;
import openrsc.bot.render.WorldRenderer;
import orsc.graphics.three.MeshExporter;
import orsc.graphics.three.RSModel;
import orsc.graphics.three.RegionExporter;

/**
 * Bakes every scenery model once, so the viewer can place scenery itself: {@code objlib.bin} holds
 * the models and {@code objlib.json} indexes them and maps object ids to them.
 *
 * <p>A model is stored at direction 0 in anchor-local engine units, with its vertex shades for all 8
 * directions, since the engine lights each direction apart and the viewer rotates the geometry
 * itself. Its bytes, big-endian: u16 group count; per group i16 texture, i32 vertex count n, n
 * times i16 x, y, z, n times u8 r, g, b, then for a texture of 0 or more n times i16 u, v (times
 * 512), and last 8 times n u8 shades, direction by direction.
 */
public final class ObjectLibExporter {

    private record ModelSpan(int model, int off, int len) {}

    /** {@code lift} raises the model, in engine units; {@code cmd1, cmd2} are null for none, WalkTo or Examine. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ObjEntry(int id, int model, int w, int h, int lift, String name, String cmd1, String cmd2) {}

    private record AnimEntry(int id, List<Integer> frames) {}

    private record ObjLib(
            long baked,
            List<ModelSpan> models,
            List<ObjEntry> objects,
            List<AnimEntry> anims,
            List<Integer> translucent) {}

    /**
     * The models the client draws translucent, picked by name.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L14427">mudclient.loadModels</a>
     */
    private static final Set<String> TRANSLUCENT_MODELS = Set.of("giantcrystal");

    /**
     * The client's animated scenery: object id to the models it cycles through.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L4964">mudclient.drawGame</a>
     */
    private static final Map<Integer, String[]> ANIM_FRAMES = new LinkedHashMap<>();

    static {
        ANIM_FRAMES.put(97, new String[] {"firea1", "firea2", "firea3"});
        ANIM_FRAMES.put(274, new String[] {"fireplacea1", "fireplacea2", "fireplacea3"});
        ANIM_FRAMES.put(51, new String[] {"torcha1", "torcha2", "torcha3", "torcha4"});
        ANIM_FRAMES.put(143, new String[] {"skulltorcha1", "skulltorcha2", "skulltorcha3", "skulltorcha4"});
        ANIM_FRAMES.put(1031, new String[] {"lightning1", "lightning2", "lightning3"});
        ANIM_FRAMES.put(1036, new String[] {"firespell1", "firespell2", "firespell3"});
        ANIM_FRAMES.put(1147, new String[] {"spellcharge1", "spellcharge2", "spellcharge3"});
        ANIM_FRAMES.put(1142, new String[] {"clawspell1", "clawspell2", "clawspell3", "clawspell4", "clawspell5"});
    }

    /**
     * Registers the animation frames' models, which no def names, so that
     * {@code WorldRenderer.loadModels} loads them; {@link #export} bakes only loaded models.
     */
    public static void registerAnimModels() {
        for (String[] frames : ANIM_FRAMES.values()) {
            for (String name : frames) {
                EntityHandler.storeModel(name);
            }
        }
    }

    public static void export(WorldRenderer r, File outDir, Consumer<String> log) throws IOException {
        ByteArrayOutputStream blob = new ByteArrayOutputStream(8 << 20);
        Map<Integer, int[]> modelSpans = new LinkedHashMap<>();
        List<ObjEntry> objectEntries = new ArrayList<>();

        int baked = 0;
        for (int id = 0; id < 4000; id++) {
            var def = ObjectSpriteRenderer.objectDef(id);
            if (def == null) {
                break;
            }
            int modelId = def.modelID;
            if (!modelSpans.containsKey(modelId)) {
                byte[] entry = bakeModel(r, id);
                if (entry != null) {
                    modelSpans.put(modelId, new int[] {blob.size(), entry.length});
                    blob.write(entry);
                    baked++;
                } else {
                    modelSpans.put(modelId, null);
                }
            }
            if (modelSpans.get(modelId) == null) {
                continue;
            }
            String cmd1 = def.getCommand1();
            String cmd2 = def.getCommand2();
            if (cmd2 != null && cmd2.equalsIgnoreCase("Examine")) {
                cmd2 = null;
            }
            objectEntries.add(new ObjEntry(
                    id,
                    modelId,
                    def.getWidth(),
                    def.getHeight(),
                    // The client raises the windmill sails by 480.
                    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L14497
                    id == 74 ? 480 : 0,
                    def.getName(),
                    BakeJson.menuCmd(cmd1),
                    BakeJson.menuCmd(cmd2)));
        }

        List<AnimEntry> animEntries = new ArrayList<>();
        for (Map.Entry<Integer, String[]> e : ANIM_FRAMES.entrySet()) {
            int id = e.getKey();
            if (ObjectSpriteRenderer.objectDef(id) == null) {
                continue;
            }
            List<Integer> frameIds = new ArrayList<>();
            boolean ok = true;
            for (String name : e.getValue()) {
                int modelId = EntityHandler.storeModel(name);
                if (!modelSpans.containsKey(modelId)) {
                    byte[] entry = bakeModel(r, id, modelId);
                    modelSpans.put(modelId, entry == null ? null : new int[] {blob.size(), entry.length});
                    if (entry != null) {
                        blob.write(entry);
                        baked++;
                    }
                }
                if (modelSpans.get(modelId) == null) {
                    ok = false;
                    break;
                }
                frameIds.add(modelId);
            }
            if (ok) {
                animEntries.add(new AnimEntry(id, frameIds));
            }
        }

        List<ModelSpan> models = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : modelSpans.entrySet()) {
            if (e.getValue() != null) {
                models.add(new ModelSpan(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
        }

        List<Integer> translucent = new ArrayList<>();
        for (ModelSpan m : models) {
            if (TRANSLUCENT_MODELS.contains(EntityHandler.getModelName(m.model()))) {
                translucent.add(m.model());
            }
        }

        Files.write(new File(outDir, "objlib.bin").toPath(), blob.toByteArray());
        BakeJson.MAPPER.writeValue(
                new File(outDir, "objlib.json"),
                new ObjLib(System.currentTimeMillis(), models, objectEntries, animEntries, translucent));
        log.accept("object library: " + baked + " models, " + objectEntries.size()
                + " object ids, " + (blob.size() / 1024) + " KB, "
                + translucent.size() + " translucent");
    }

    private static byte[] bakeModel(WorldRenderer r, int objectId) throws IOException {
        var def = ObjectSpriteRenderer.objectDef(objectId);
        return def == null ? null : bakeModel(r, objectId, def.modelID);
    }

    private static byte[] bakeModel(WorldRenderer r, int objectId, int modelId) throws IOException {
        List<List<MeshExporter.Group>> byDir = new ArrayList<>(8);
        for (int dir = 0; dir < 8; dir++) {
            RSModel m = r.placeObjectClient(objectId, 50, 50, dir, modelId);
            if (m == null) {
                return null;
            }
            MeshExporter ex = new MeshExporter();
            RegionExporter.exportAnchorLocal(m, ex);
            r.removeModel(m);
            List<MeshExporter.Group> gs = new ArrayList<>();
            for (MeshExporter.Group g : ex.groups()) {
                if (!g.positions.isEmpty()) {
                    gs.add(g);
                }
            }
            byDir.add(gs);
        }
        // One geometry serves all eight shade sets, so every direction must have the same groups.
        List<MeshExporter.Group> d0 = byDir.get(0);
        if (d0.isEmpty()) {
            return null;
        }
        for (int dir = 1; dir < 8; dir++) {
            if (byDir.get(dir).size() != d0.size()) {
                return null;
            }
            for (int g = 0; g < d0.size(); g++) {
                if (byDir.get(dir).get(g).positions.size()
                                != d0.get(g).positions.size()
                        || byDir.get(dir).get(g).texture != d0.get(g).texture) {
                    return null;
                }
            }
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeShort(d0.size());
        for (int g = 0; g < d0.size(); g++) {
            MeshExporter.Group g0 = d0.get(g);
            int n = g0.positions.size();
            out.writeShort(g0.texture);
            out.writeInt(n);
            for (float[] p : g0.positions) {
                out.writeShort(clampI16(Math.round(p[0])));
                out.writeShort(clampI16(Math.round(p[1])));
                out.writeShort(clampI16(Math.round(p[2])));
            }
            for (float[] c : g0.bases) {
                out.writeByte(Math.round(c[0] * 255));
                out.writeByte(Math.round(c[1] * 255));
                out.writeByte(Math.round(c[2] * 255));
            }
            if (g0.texture >= 0) {
                for (float[] uv : g0.uvs) {
                    out.writeShort(clampI16(Math.round(uv[0] * 512)));
                    out.writeShort(clampI16(Math.round(uv[1] * 512)));
                }
            }
            for (int dir = 0; dir < 8; dir++) {
                for (int sIdx = 0; sIdx < n; sIdx++) {
                    out.writeByte(byDir.get(dir).get(g).shades.get(sIdx));
                }
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    private static int clampI16(int v) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private ObjectLibExporter() {}
}
