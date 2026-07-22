package openrsc.maprender.bake;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.render.ObjectSpriteRenderer;
import openrsc.bot.render.WorldRenderer;
import orsc.graphics.three.MeshExporter;
import orsc.graphics.three.RSModel;
import orsc.graphics.three.RegionExporter;

/**
 * Bakes the object-model LIBRARY for the 3D viewer: one entry per distinct
 * model, storing dir-0 geometry in anchor-local coordinates plus the vertex
 * shades for ALL 8 directions (rotation is the only geometric difference
 * between directions, but the engine relights per direction — so geometry is
 * shared and shading is per-dir; the viewer rotates positions itself).
 *
 * <p>This is what lets the viewer assemble scenery client-side from
 * placements — and therefore swap a mined-out rock or a cut tree in place
 * when a bot observes it, instead of shipping scenery fused into cell meshes.
 *
 * <p>{@code objlib.bin} (big-endian): per model, at the offset given in the
 * index: u16 groupCount; per group: i16 tex; i32 n; n×(i16 x,y,z) dir-0
 * anchor-local; n×(u8 r,g,b) base; tex≥0: n×(i16 u*512,v*512); then
 * 8 × n × u8 vertex shades (dirs 0..7).
 *
 * <p>{@code objlib.json}: {@code {baked, models:[{model,off,len}],
 * objects:[{id,model,w,h,lift,name}]}}.
 */
public final class ObjectLibExporter {

  /** Where a model's baked geometry lives in {@code objlib.bin} ({@code off}..{@code off+len}). */
  private record ModelSpan(int model, int off, int len) {}

  /**
   * One object id → its model + footprint + menu commands. {@code cmd1}/{@code cmd2} are present
   * only for real actions (the stock "WalkTo"/"Examine" sentinels are omitted).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record ObjEntry(int id, int model, int w, int h, int lift, String name,
                          String cmd1, String cmd2) {}

  /** An animated object id → the model ids of its cycled frames. */
  private record AnimEntry(int id, List<Integer> frames) {}

  private record ObjLib(long baked, List<ModelSpan> models, List<ObjEntry> objects,
                        List<AnimEntry> anims) {}

  /**
   * The client's animated scenery (mudclient_animate_objects): object id → its frame MODEL
   * names, cycled every 6 client frames (120ms). Frame 1 is the def's own model; the alternates
   * are separate models in models.orsc that no def references, so they must be registered
   * ({@link #registerAnimModels}) BEFORE {@code WorldRenderer.loadModels()} caches by name.
   * (Windmill sails, id 74, rotate continuously instead — not modelled here.)
   */
  private static final Map<Integer, String[]> ANIM_FRAMES = new LinkedHashMap<>();

  static {
    ANIM_FRAMES.put(97, new String[]{"firea1", "firea2", "firea3"});
    ANIM_FRAMES.put(274, new String[]{"fireplacea1", "fireplacea2", "fireplacea3"});
    ANIM_FRAMES.put(51, new String[]{"torcha1", "torcha2", "torcha3", "torcha4"});
    ANIM_FRAMES.put(143, new String[]{"skulltorcha1", "skulltorcha2", "skulltorcha3",
        "skulltorcha4"});
    ANIM_FRAMES.put(1031, new String[]{"lightning1", "lightning2", "lightning3"});
    ANIM_FRAMES.put(1036, new String[]{"firespell1", "firespell2", "firespell3"});
    ANIM_FRAMES.put(1147, new String[]{"spellcharge1", "spellcharge2", "spellcharge3"});
    ANIM_FRAMES.put(1142, new String[]{"clawspell1", "clawspell2", "clawspell3", "clawspell4",
        "clawspell5"});
  }

  /** Register every animation frame model name so {@code loadModels()} caches them. */
  public static void registerAnimModels() {
    for (String[] frames : ANIM_FRAMES.values()) {
      for (String name : frames) {
        com.openrsc.client.entityhandling.EntityHandler.storeModel(name);
      }
    }
  }

  public static void export(WorldRenderer r, File outDir,
      java.util.function.Consumer<String> log) throws IOException {
    ByteArrayOutputStream blob = new ByteArrayOutputStream(8 << 20);
    Map<Integer, int[]> modelSpans = new LinkedHashMap<>(); // modelId -> {off,len}
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
          modelSpans.put(modelId, new int[]{blob.size(), entry.length});
          blob.write(entry);
          baked++;
        } else {
          modelSpans.put(modelId, null);
        }
      }
      if (modelSpans.get(modelId) == null) {
        continue;
      }
      // Menu command names for the remote-control context menu. Stock
      // sentinels (mudclient object menu): command1 "WalkTo" and command2
      // "Examine" mean "no action" — omitted so the menu skips them.
      String cmd1 = def.getCommand1();
      String cmd2 = def.getCommand2();
      if (cmd2 != null && cmd2.equalsIgnoreCase("Examine")) {
        cmd2 = null;
      }
      objectEntries.add(new ObjEntry(id, modelId, def.getWidth(), def.getHeight(),
          id == 74 ? 480 : 0, def.getName(), menuCmd(cmd1), menuCmd(cmd2)));
    }

    // Animation frames: bake each frame model with its object's placement
    // recipe, and index id → frame model ids for the viewer's animator.
    List<AnimEntry> animEntries = new ArrayList<>();
    for (Map.Entry<Integer, String[]> e : ANIM_FRAMES.entrySet()) {
      int id = e.getKey();
      if (ObjectSpriteRenderer.objectDef(id) == null) {
        continue;
      }
      List<Integer> frameIds = new ArrayList<>();
      boolean ok = true;
      for (String name : e.getValue()) {
        int modelId = com.openrsc.client.entityhandling.EntityHandler.storeModel(name);
        if (!modelSpans.containsKey(modelId)) {
          byte[] entry = bakeModel(r, id, modelId);
          modelSpans.put(modelId, entry == null ? null : new int[]{blob.size(), entry.length});
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

    Files.write(new File(outDir, "objlib.bin").toPath(), blob.toByteArray());
    BakeJson.MAPPER.writeValue(new File(outDir, "objlib.json"),
        new ObjLib(System.currentTimeMillis(), models, objectEntries, animEntries));
    log.accept("object library: " + baked + " models, " + objectEntries.size()
        + " object ids, " + (blob.size() / 1024) + " KB");
  }

  /** Bake one model (via any object id that uses it): dir-0 geometry + 8 shade sets. */
  private static byte[] bakeModel(WorldRenderer r, int objectId) throws IOException {
    var def = ObjectSpriteRenderer.objectDef(objectId);
    return def == null ? null : bakeModel(r, objectId, def.modelID);
  }

  /** As {@link #bakeModel(WorldRenderer, int)} with an explicit model (animation frames). */
  private static byte[] bakeModel(WorldRenderer r, int objectId, int modelId) throws IOException {
    // Export all 8 directions; group/vertex order is identical across dirs
    // (rotation is applied to the same clone pipeline), verified below.
    List<MeshExporter.Group>[] byDir = new List[8];
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
      byDir[dir] = gs;
    }
    List<MeshExporter.Group> d0 = byDir[0];
    if (d0.isEmpty()) {
      return null;
    }
    for (int dir = 1; dir < 8; dir++) {
      if (byDir[dir].size() != d0.size()) {
        return null; // structure diverged (shouldn't happen) — skip model
      }
      for (int g = 0; g < d0.size(); g++) {
        if (byDir[dir].get(g).positions.size() != d0.get(g).positions.size()
            || byDir[dir].get(g).texture != d0.get(g).texture) {
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
          out.writeByte(byDir[dir].get(g).shades.get(sIdx));
        }
      }
    }
    out.flush();
    return bos.toByteArray();
  }

  private static int clampI16(int v) {
    return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
  }

  /**
   * A context-menu command, or {@code null} when it's the stock "no action"
   * sentinel (command "WalkTo", or blank). The "Examine" default on command2 is
   * cleared by the caller before this is applied.
   */
  private static String menuCmd(String cmd) {
    if (cmd == null || cmd.isBlank() || cmd.equalsIgnoreCase("WalkTo")) {
      return null;
    }
    return cmd;
  }

  private ObjectLibExporter() {}
}
