package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.DoorDef;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.BoundaryLocs;

/**
 * Door/boundary data for the 3D viewer's client-side wall-edge assembly:
 * <ul>
 *   <li>{@code doorlib.json} — every boundary def the client renders with:
 *       wall height + front/back texture (a boundary is ONE quad);</li>
 *   <li>{@code boundaries.json} — the static placements (stripped from the
 *       baked walls), which the viewer draws by default and swaps per-edge
 *       when a bot observes different state (opened doors).</li>
 * </ul>
 */
public final class DoorLibExporter {

  public static void export(List<BoundaryLocs.Loc> boundaries, File outDir,
      java.util.function.Consumer<String> log) throws IOException {
    List<String> defs = new ArrayList<>();
    for (int id = 0; id < 512; id++) {
      DoorDef d = EntityHandler.getDoorDef(id);
      if (d == null) {
        break;
      }
      // cmd1/cmd2 feed the remote-control context menu ("Open", "Picklock"…).
      // Stock sentinels (mudclient wall menu): command1 "WalkTo" and
      // command2 "Examine" mean "no action" — omitted.
      String cmd1 = d.getCommand1();
      String cmd2 = d.getCommand2();
      if (cmd2 != null && cmd2.equalsIgnoreCase("Examine")) {
        cmd2 = null;
      }
      defs.add("{\"id\":" + id
          + ",\"height\":" + d.getWallObjectHeight()
          + ",\"texF\":" + d.getModelVar2()
          + ",\"texB\":" + d.getModelVar3()
          + ",\"doorType\":" + d.getDoorType()
          + (cmd1 == null || cmd1.isBlank() || cmd1.equalsIgnoreCase("WalkTo")
              ? "" : ",\"cmd1\":\"" + cmd1.replace("\"", "") + "\"")
          + (cmd2 == null || cmd2.isBlank() || cmd2.equalsIgnoreCase("WalkTo")
              ? "" : ",\"cmd2\":\"" + cmd2.replace("\"", "") + "\"")
          + ",\"name\":\"" + d.getName().replace("\"", "") + "\"}");
    }
    try (PrintWriter w = new PrintWriter(new File(outDir, "doorlib.json"), StandardCharsets.UTF_8)) {
      w.print("{\"doors\":[" + String.join(",", defs) + "]}");
    }

    List<String> locs = new ArrayList<>(boundaries.size());
    for (BoundaryLocs.Loc b : boundaries) {
      locs.add("{\"id\":" + b.id() + ",\"x\":" + b.pos().x()
          + ",\"z\":" + (b.pos().y() % 944) + ",\"floor\":" + (b.pos().y() / 944)
          + ",\"dir\":" + b.direction() + "}");
    }
    try (PrintWriter w = new PrintWriter(new File(outDir, "boundaries.json"), StandardCharsets.UTF_8)) {
      w.print("{\"boundaries\":[" + String.join(",", locs) + "]}");
    }
    log.accept("door library: " + defs.size() + " defs, " + locs.size() + " placements");
  }

  public static void main(String[] args) throws Exception {
    // Standalone (dev-harness rebakes): needs the client defs + server locs.
    orsc.Config.F_CACHE_DIR = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
    orsc.Config.S_WANT_CUSTOM_SPRITES = false;
    EntityHandler.load(false);
    var conf = openrsc.gamedata.ServerConf.resolve();
    List<BoundaryLocs.Loc> boundaries =
        BoundaryLocs.load(conf.locs().resolve("BoundaryLocs.json"));
    export(boundaries, new File(args.length > 0 ? args[0] : "/tmp/doorlib"),
        System.out::println);
  }

  private DoorLibExporter() {}
}
