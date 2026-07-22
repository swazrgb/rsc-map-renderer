package openrsc.maprender.bake;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.DoorDef;
import java.io.File;
import java.io.IOException;
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

  /**
   * One boundary def (a boundary is ONE quad). {@code cmd1}/{@code cmd2} feed the
   * remote-control context menu and are present only for real actions — the stock
   * sentinels ("WalkTo"/"Examine" = no action) are omitted.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private record DoorDefJson(int id, int height, int texF, int texB, int doorType,
                             String cmd1, String cmd2, String name) {}

  private record DoorLib(List<DoorDefJson> doors) {}

  /** One static placement, floor split out of the boundary's absolute Y. */
  private record BoundaryJson(int id, int x, int z, int floor, int dir) {}

  private record Boundaries(List<BoundaryJson> boundaries) {}

  public static void export(List<BoundaryLocs.Loc> boundaries, File outDir,
      java.util.function.Consumer<String> log) throws IOException {
    List<DoorDefJson> defs = new ArrayList<>();
    for (int id = 0; id < 512; id++) {
      DoorDef d = EntityHandler.getDoorDef(id);
      if (d == null) {
        break;
      }
      String cmd1 = d.getCommand1();
      String cmd2 = d.getCommand2();
      if (cmd2 != null && cmd2.equalsIgnoreCase("Examine")) {
        cmd2 = null;
      }
      defs.add(new DoorDefJson(id, d.getWallObjectHeight(), d.getModelVar2(), d.getModelVar3(),
          d.getDoorType(), menuCmd(cmd1), menuCmd(cmd2), d.getName()));
    }
    BakeJson.MAPPER.writeValue(new File(outDir, "doorlib.json"), new DoorLib(defs));

    List<BoundaryJson> locs = new ArrayList<>(boundaries.size());
    for (BoundaryLocs.Loc b : boundaries) {
      locs.add(new BoundaryJson(b.id(), b.pos().x(),
          b.pos().y() % 944, b.pos().y() / 944, b.direction()));
    }
    BakeJson.MAPPER.writeValue(new File(outDir, "boundaries.json"), new Boundaries(locs));
    log.accept("door library: " + defs.size() + " defs, " + locs.size() + " placements");
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
