package openrsc.maprender.bake;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.DoorDef;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import openrsc.bot.render.WorldRenderer;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.defs.BoundaryLocs;

/**
 * Writes {@code doorlib.json}: every boundary def the client renders, by id, with its wall height,
 * front and back texture and menu commands. The placements are in {@code boundaries.json}; the
 * viewer draws each boundary itself rather than from the baked wall meshes.
 */
public final class DoorLibExporter {

    /** {@code cmd1} and {@code cmd2} are {@code null} for none, WalkTo or Examine. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DoorDefJson(
            int id, int height, int texF, int texB, int doorType, String cmd1, String cmd2, String name) {}

    private record DoorLib(List<DoorDefJson> doors) {}

    /** Rebakes only the door library: {@code DoorLibExporter [outDir] [clientCacheDir]}. */
    public static void main(String[] args) throws Exception {
        WorldRenderer.configureCache(args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache");
        var conf = ServerConf.resolve();
        List<BoundaryLocs.Loc> boundaries = BoundaryLocs.load(conf.locs().resolve("BoundaryLocs.json"));
        export(boundaries, new File(args.length > 0 ? args[0] : "/tmp/doorlib"), System.out::println);
    }

    /**
     * Reads the client defs, so {@link WorldRenderer#configureCache} must have run first.
     * {@code boundaries} is not read.
     */
    public static void export(List<BoundaryLocs.Loc> boundaries, File outDir, Consumer<String> log) throws IOException {
        List<DoorDefJson> defs = new ArrayList<>();
        for (int id = 0; id < EntityHandler.doorCount(); id++) {
            DoorDef d = EntityHandler.getDoorDef(id);
            if (d == null) {
                continue;
            }
            String cmd1 = d.getCommand1();
            String cmd2 = d.getCommand2();
            if (cmd2 != null && cmd2.equalsIgnoreCase("Examine")) {
                cmd2 = null;
            }
            defs.add(new DoorDefJson(
                    id,
                    d.getWallObjectHeight(),
                    d.getModelVar2(),
                    d.getModelVar3(),
                    d.getDoorType(),
                    BakeJson.menuCmd(cmd1),
                    BakeJson.menuCmd(cmd2),
                    d.getName()));
        }
        BakeJson.MAPPER.writeValue(new File(outDir, "doorlib.json"), new DoorLib(defs));
        log.accept("door library: " + defs.size() + " defs");
    }

    private DoorLibExporter() {}
}
