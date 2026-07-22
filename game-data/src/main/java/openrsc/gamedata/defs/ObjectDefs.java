package openrsc.gamedata.defs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import openrsc.gamedata.defs.xml.XmlTree;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code ObjectDefs.json} loader — scenery definitions indexed by object id. The collision map
 * consults {@code Typ}/{@code Width}/{@code Height} to mark occupied tiles unwalkable, mirroring
 * plutonium {@code world.go:setUnwalkableTiles}.
 */
public final class ObjectDefs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(@JsonProperty("ID") int id,
                      @JsonProperty("Name") String name,
                      @JsonProperty("Typ") int typ,
                      @JsonProperty("Width") int width,
                      @JsonProperty("Height") int height,
                      @JsonProperty("Command1") String command1) {

  }

  private final Entry[] byId;

  private ObjectDefs(Entry[] byId) {
    this.byId = byId;
  }

  /**
   * True iff the scenery is a door — {@code Command1} is either {@code "open"} (currently-closed
   * state) or {@code "close"} (currently-open state), case-insensitive. The other typ=2 verbs
   * ({@code "talk through"}, {@code "jump off"}, {@code "Look"}, {@code "climb on"}) are special
   * quest interactions, not doors, so this returns false for them.
   *
   * <p>Used by both:
   * <ul>
   *   <li>{@link openrsc.gamedata.world.CollisionMap#applyScenery} — to
   *       decide which typ=2 walls to skip from the static stamp. Both
   *       door states are passable (open is already passable, closed will
   *       be opened by the navigator), so we skip both.</li>
   *   <li>{@link openrsc.gamedata.world.PacketDispatcher} opcode-48 handler
   *       — to identify candidates for the runtime wall-overlay update.
   *       Within that handler we additionally check {@code Command1="open"}
   *       to stamp the closed state only.</li>
   * </ul>
   */
  public boolean isOpenable(int id) {
    Entry e = get(id);
    if (e == null) {
      return false;
    }
    String cmd = e.command1();
    if (cmd == null) {
      return false;
    }
    return cmd.equalsIgnoreCase("open") || cmd.equalsIgnoreCase("close");
  }

  /**
   * Subset of {@link #isOpenable}: true iff the door is currently in the
   * closed state ({@code Command1.equalsIgnoreCase("open")}). The runtime
   * wall overlay tracks closed-state stamps only — an open-state door
   * variant (Command1="close" or "WalkTo") never blocks. Used by
   * {@link openrsc.gamedata.world.PacketDispatcher} for opcode-48 updates.
   */
  /**
   * True iff this typ=2 scenery is one of the server's {@code DoorAction.handleObjectDoor} /
   * {@code handleGates} unconditional toggle ids — the generic free gates/doors anyone can swing
   * open without a quest stage, skill, or item.
   *
   * <p>Per-coord exceptions (locked bank doors at 467,518 and 558,587;
   * Al Kharid toll gate at 92,649; members-only gates; etc.) are covered by {@link SpecialDoor}
   * entries — call sites should consult that registry FIRST, falling back to this name+id whitelist
   * when no override is found.
   *
   * <p>Whitelist (closed-state typ=2 ids):
   * <ul>
   *   <li>57 — Metal gate generic</li>
   *   <li>60 — Wooden gate generic</li>
   *   <li>64 — Bank doors (with locked exceptions in SpecialDoor)</li>
   * </ul>
   */
  public boolean isGenericFreeDoor(int id) {
    return id == 57 || id == 60 || id == 64;
  }

  public boolean isClosedDoor(int id) {
    Entry e = get(id);
    if (e == null) {
      return false;
    }
    String cmd = e.command1();
    return cmd != null && cmd.equalsIgnoreCase("open");
  }

  /**
   * True if this scenery is a climbable transport (ladder, stairs, manhole) — i.e. the player can
   * stand on its tile and use the primary verb to teleport to another floor. Stamping these as full
   * blocks in the static collision map prevents the pathfinder from reaching the ladder tile
   * itself, even though the bot needs to occupy it to interact. The {@code Climb-*} / {@code Go up}
   * / {@code Go down} / {@code climb down} verbs are the canonical RSC climb commands per
   * {@code authentic/defaults/Ladders.java}.
   */
  public boolean isClimbable(int id) {
    Entry e = get(id);
    if (e == null) {
      return false;
    }
    String cmd = e.command1();
    if (cmd == null) {
      return false;
    }
    String c = cmd.toLowerCase();
    return c.equals("climb-up") || c.equals("climb-down")
           || c.equals("climb up") || c.equals("climb down")
           || c.equals("go up") || c.equals("go down");
  }

  public static ObjectDefs load(Path file) throws IOException {
    ObjectMapper m = new ObjectMapper();
    List<Entry> list = m.readValue(
        Files.readAllBytes(file),
        m.getTypeFactory().constructCollectionType(List.class, Entry.class));
    int max = 0;
    for (Entry e : list) {
      if (e.id() > max) {
        max = e.id();
      }
    }
    Entry[] arr = new Entry[max + 1];
    for (Entry e : list) {
      arr[e.id()] = e;
    }
    return new ObjectDefs(arr);
  }

  /**
   * Loads the server's authoritative {@code defs/GameObjectDef.xml} ({@code <GameObjectDef-array>},
   * positional ids — mirrors the server's {@code EntityHandler} XStream binding; {@code type} is
   * what the old JSON dump called {@code Typ}).
   */
  public static ObjectDefs loadXml(Path file) {
    List<XmlTree> defs =
        XmlTree.parse(file).children("GameObjectDef");
    Entry[] arr = new Entry[defs.size()];
    for (int id = 0; id < defs.size(); id++) {
      var d = defs.get(id);
      arr[id] = new Entry(id, d.text("name"), d.intOf("type", 0),
          d.intOf("width", 1), d.intOf("height", 1), d.text("command1"));
    }
    return new ObjectDefs(arr);
  }

  public Entry get(int id) {
    return (id >= 0 && id < byId.length) ? byId[id] : null;
  }

  public int size() {
    return byId.length;
  }
}
