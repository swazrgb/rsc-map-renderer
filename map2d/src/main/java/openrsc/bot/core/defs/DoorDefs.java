package openrsc.bot.core.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import openrsc.bot.core.defs.xml.XmlTree;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code DoorDefs.json} loader; collision map consults {@code DoorType} and {@code Unknown}.
 */
public final class DoorDefs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(@JsonProperty("Name") String name,
                      @JsonProperty("DoorType") int doorType,
                      @JsonProperty("Unknown") int unknown,
                      @JsonProperty("Command1") String command1) {

  }

  private final Entry[] byId;

  private DoorDefs(Entry[] byId) {
    this.byId = byId;
  }

  /**
   * Boundary equivalent of {@link ObjectDefs#isOpenable(int)}. Only meaningful for
   * {@code doorType == 1} boundaries (the others are decoration or projectile-only and never affect
   * walking).
   */
  public boolean isOpenable(int id) {
    if (id < 0 || id >= byId.length) {
      return false;
    }
    Entry e = byId[id];
    if (e.doorType() != 1) {
      return false;
    }
    String cmd = e.command1();
    if (cmd == null || cmd.isEmpty()) {
      return false;
    }
    return !cmd.equalsIgnoreCase("WalkTo");
  }

  public String command1(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].command1() : null;
  }

  public String name(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].name() : null;
  }

  /**
   * True iff the boundary is one the server's
   * {@link com.openrsc.server.plugins.authentic.defaults.DoorAction} toggles unconditionally — used
   * by {@link openrsc.bot.core.world.CollisionMap} to decide "passable in the static map?".
   *
   * <p>The rule mirrors DoorAction's bottom-of-switch arms:
   * <ul>
   *   <li>Names "Doorframe" / "Door" / "Odd looking wall" toggle without
   *       prereqs IF AND ONLY IF the id's specific switch case does
   *       (which is true for the bare DOOR/DOORFRAME/ODD_LOOKING_WALL
   *       constants but NOT for ids like Prince Ali jail or guild doors
   *       which share the "Door" name but have script overrides).</li>
   * </ul>
   * Per-id specific switch cases (Prince Ali, guild doors, etc.) get
   * {@link SpecialDoor} entries — callers should consult that registry
   * FIRST. This name-based fallback only fires when no override exists.
   *
   * <p>Concretely, free-toggle ids in vanilla OpenRSC:
   * <ul>
   *   <li>1, 11, 174 — DOORFRAME variants (cmd1 = "WalkTo")</li>
   *   <li>2, 8 — DOOR / DOOR_GRAY_BRICKS (toggle to frame)</li>
   *   <li>9 — DOORFRAME_GRAY_BRICKS</li>
   *   <li>22 — ODD_LOOKING_WALL (push secret door)</li>
   * </ul>
   */
  public boolean isGenericFreeDoor(int id) {
    if (id < 0 || id >= byId.length) {
      return false;
    }
    // Strict id whitelist — only the DOOR/DOORFRAME pair plus
    // ODD_LOOKING_WALL. Cmd1="WalkTo" alone is NOT a free-door signal:
    // the DoorDef table uses Cmd1="WalkTo" for plain walls (Wall, Fence,
    // Window, Highwall, timberwall, snowwall, ...) — boundaries with
    // doorType=1 but no "open" verb because they're not doors at all,
    // just impassable walls. Treating them as free would wipe ~46 wall
    // ids (tens of thousands of landscape-archive placements) off the
    // collision map.
    //
    // Doorframes already in their open state hit this whitelist via
    // id 1 / 9 / 11 / 174 (the "Doorframe" name is checked by id only).
    return id == 1 || id == 2 || id == 8 || id == 9
           || id == 11 || id == 22 || id == 174;
  }

  public static DoorDefs load(Path file) throws IOException {
    ObjectMapper m = new ObjectMapper();
    List<Entry> list = m.readValue(
        Files.readAllBytes(file),
        m.getTypeFactory().constructCollectionType(List.class, Entry.class));
    return new DoorDefs(list.toArray(Entry[]::new));
  }

  /**
   * Loads the server's authoritative {@code defs/DoorDef.xml} ({@code <DoorDef-array>}, positional
   * ids — mirrors the server's {@code EntityHandler} XStream binding).
   */
  public static DoorDefs loadXml(Path file) {
    List<XmlTree> defs =
        XmlTree.parse(file).children("DoorDef");
    Entry[] arr = new Entry[defs.size()];
    for (int id = 0; id < defs.size(); id++) {
      var d = defs.get(id);
      arr[id] = new Entry(d.text("name"), d.intOf("doorType", 0),
          d.intOf("unknown", 0), d.text("command1"));
    }
    return new DoorDefs(arr);
  }

  public int doorType(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].doorType() : 0;
  }

  public int unknown(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].unknown() : 0;
  }

  public int size() {
    return byId.length;
  }
}
