package scripting.idlescript;

import bot.Main;
import bot.ui.scriptselector.models.Category;
import bot.ui.scriptselector.models.ScriptInfo;
import callbacks.SleepCallback;
import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.GameObjectDef;
import controller.Controller;
import controller.WebWalker.WebWalker;
import controller.WebWalker.WebwalkEdge;
import controller.WebWalker.WebwalkGraph;
import controller.WebWalker.WebwalkNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import orsc.ORSCharacter;

/**
 * Spawn-camps the NPCs of a camp recipe with melee. While it waits at a spawn whose NPC is not in
 * view, it sends the attack for that NPC's server index every tick, so the hit lands the tick it
 * respawns. The recipe format and what the camper does are in RECIPE.md beside the map viewer's
 * script generator.
 *
 * <p>Recipes are pasted into its window and kept, one file each, in {@code
 * Cache/botconfigs/Bob_MapCamper}, named by their first comment line. Script arguments naming a
 * saved recipe start it without the window.
 */
public class Bob_MapCamper extends IdleScript {
  public static final ScriptInfo info =
      new ScriptInfo(
          new Category[] {Category.COMBAT, Category.MELEE},
          "Bob",
          "Camps the NPC spawns in its recipe and hits each the tick it respawns.");

  private static final Path RECIPES = Paths.get("Cache", "botconfigs", "Bob_MapCamper");
  private static final int POLL_MS = 10;
  /* the tick length without a world line */
  private static final int TICK_MS = 640;
  private static final int FLOOR = 944;
  private static final int INVENTORY_SIZE = 30;
  private static final int SLEEPING_BAG = 1263;
  private static final int HITS = 3;
  private static final int[] BANKERS = {95, 224, 268, 540, 617, 792};
  /*
   * The server sends us the NPCs whose straight-line distance, rounded down, is at most 15: under 16
   * tiles. From within this, squared, a diagonal step cannot take an NPC out of view, so one that
   * leaves view from this close died
   * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369
   * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L409
   */
  private static final int VIEW_SQ = 212;
  private static final int ROUTE_REACH = 30;
  private static final int STEP_TILES = 2;
  private static final int BANK_TILES = 3;
  private static final int NEAR_TILES = 5;
  private static final int LOOT_TILES = 4;
  /* a target due within this many ticks, with the player at its spawn, holds the hunt there */
  private static final int SOON_TICKS = 5;
  /* last tick's target is kept while it scores less than this much worse than the best */
  private static final int KEEP_TICKS = 2;
  /* added to a target's score when it may not be where the camper goes for it */
  private static final int UNSURE_TICKS = 6;
  /* a walk to a due spawn this much longer than the wait counts as unsure */
  private static final int WALK_SLACK_TICKS = 5;
  private static final long NOT_DUE = -1L;
  /* deposits or withdrawals still to do this long after they were sent stop the camper */
  private static final int BANK_TICKS = 3;
  private static final int STEP_LOG_TICKS = 20;
  private static final int SKIP_TICKS = 50;
  private static final int OP_BANK_CLOSE = 212;
  private static final int OP_ATTACK_NPC = 190;
  private static final int OP_TAKE = 247;

  private static final int WALK = 0;
  private static final int DOOR = 1;
  private static final int GATE = 2;
  private static final int OBJ = 3;
  private static final int WALL = 4;
  private static final int CUT = 5;
  private static final int USEWALL = 6;
  private static final int USEOBJ = 7;
  private static final String[] KINDS = {
    "walk", "door", "gate", "obj", "wall", "cut", "usewall", "useobj"
  };
  /* how many numbers each step takes */
  private static final int[] NUMBERS = {2, 4, 3, 5, 6, 5, 6, 5};

  /* a walk round the block NPCs searches the box of the player and its tile grown by this */
  private static final int SEARCH_TILES = 16;
  private static final int OP_WALK = 187;
  /* the client's collision flags */
  private static final int WALL_NORTH = 1, WALL_EAST = 2, WALL_SOUTH = 4, WALL_WEST = 8;
  private static final int FULL_BLOCK = 16 | 32 | 64;
  private static final int SOURCE_SOUTH = 1, SOURCE_WEST = 2, SOURCE_NORTH = 4, SOURCE_EAST = 8;

  private static final class Target {
    int index, npcId, x, z, respawn, minX, minZ, maxX, maxZ;
    long due = NOT_DUE;
    long skipUntil;
    /* where the last update saw it, when it was in view */
    boolean seen, seenFighting;
    int seenX, seenZ;
    /* where it walked out of view far away */
    boolean hasTile;
    int tileX, tileZ;
    /* updates spent hunting it within a tile of its spawn while it was out of view */
    int waiting;
  }

  private static final class Carry {
    int id, min, target;
    boolean wear;
  }

  private static final class Step {
    int kind, x, z, dir, closedId, cmd, item, landX, landZ, line;
    String text;

    boolean lands() {
      return kind == OBJ || kind == WALL || kind == USEWALL || kind == USEOBJ;
    }
  }

  private long tickMs;
  private final List<Target> targets = new ArrayList<>();
  private final Map<Integer, Target> byIndex = new HashMap<>();
  private int campX, campZ, campMinX, campMinZ, campMaxX, campMaxZ;
  private int bankX, bankZ;
  private int style = -1;
  private int eatAt = -1;
  /* The fatigue in percent to sleep at; -1 for never. */
  private int sleepAt = -1;
  private int[] foods = new int[0];
  private final List<Carry> carries = new ArrayList<>();
  private int[] loot = new int[0];
  private int[] bury = new int[0];
  private int[] block = new int[0];
  private Step[] bankRoute;
  private Step[] campRoute;

  private long startMs = -1L;
  private volatile long tick;
  private boolean decided;
  private boolean stopped;
  private boolean loggedOut;
  private int px, pz;
  private TickHook hook;
  private long hookTicks;
  /* the server updates the client applied since the last poll; observed together, as one */
  private long applied = 1L;
  private boolean noHook;
  /*
   * The camper, not the client, calls IdleRSC's sleep hooks, which read and send the sleep word:
   * each true when the client was built without the patch that calls that hook.
   */
  private boolean imageHook, fatigueHook;
  private boolean noSleepLogged;
  private Field inStream, packetLength;
  /* when the last update was counted, to move the tick count on over a reconnect */
  private long updateMs;
  private volatile Map<Integer, ORSCharacter> visible = new HashMap<>();
  private boolean camping;
  /* the target the last hunt chose, and the tile it walked to or waited on; null once not camping */
  private volatile Target heading;
  private int[] huntTile;
  private volatile long preAttackTick = -1L;
  private boolean attackedBlocker;
  /* the server index of the last block NPC attacked as in the way */
  private int blockerIndex = -1;
  private volatile boolean standing;
  private volatile String status = "Starting";
  private boolean outOfFood;

  /* the route under way; at its end, stepIndex is its length */
  private Step[] route;
  private int stepIndex;
  /* the furthest step reached since the route started, when, and when that was last logged */
  private int furthest;
  private long furthestTick, stallLogTick;

  /* the tile the long-distance walker heads for while no step is near enough to resume */
  private Step[] farRoute;
  private int[] farTile;
  private final Set<Long> farTried = new HashSet<>();
  private boolean webPaths = true;
  private Object webGraph;
  private Method pathSearch, labelHandler;

  /* "deposits" or "withdrawals" while some are still to do, and the tick they were first sent */
  private String batch;
  private long batchTick;

  @Override
  public int start(String[] parameters) {
    if (startMs < 0L) {
      if (!chooseRecipe(parameters)) {
        controller.stop();
        return 1000;
      }
      startMs = System.currentTimeMillis();
      imageHook = !callsSleepCallback("orsc/PacketHandler.class");
      fatigueHook = !callsSleepCallback("orsc/mudclient.class");
      controller.log(
          "Camping "
              + targets.size()
              + " spawns around "
              + campX
              + ","
              + campZ
              + "; banking at "
              + bankX
              + ","
              + bankZ);
    }
    while (controller.isRunning() && !stopped) {
      if (!controller.isLoggedIn()) {
        loggedOut = true;
      } else {
        try {
          poll();
        } catch (RuntimeException e) {
          stop("Error: " + e);
        }
      }
      controller.sleep(POLL_MS);
    }
    return 1000;
  }

  /* Nothing static changes while running: the recipe is constant. */
  @Override
  public void cleanup() {}

  private void poll() {
    Object death = controller.getMudClientValue("deathScreenTimeout");
    if (death instanceof Integer && (Integer) death != 0) {
      stop("Died: start it again");
      return;
    }
    if (!newTick()) return;
    if (hook != null) hook.drainSleeps();
    if (loggedOut) {
      loggedOut = false;
      // What left view while logged out did not die.
      for (Target t : targets) t.seen = false;
      visible = new HashMap<>();
    }
    px = controller.currentX();
    pz = controller.currentY();
    observe();
    if (!decided && inCamp(px, pz) && reaches(campX, campZ)) camping = true;
    decided = true;
    act();
  }

  /*
   * The client class calls IdleRSC's SleepCallback itself: a client patched with the sleep adapter
   * calls its image hook from PacketHandler.showSleepScreen, and with the fatigue adapter its
   * fatigue hook from mudclient.setFatigueSleeping. Then the camper must not call that hook as
   * well. Unreadable counts as not.
   */
  private boolean callsSleepCallback(String classFile) {
    ClassLoader loader = controller.mud.getClass().getClassLoader();
    try (InputStream in = loader.getResourceAsStream(classFile)) {
      if (in == null) return false;
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
      return new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
          .contains("callbacks/SleepCallback");
    } catch (IOException e) {
      return false;
    }
  }

  private void stop(String why) {
    controller.log(why, "red");
    controller.setAutoLogin(false);
    controller.stop();
    stopped = true;
  }

  /*
   * Counts the server updates the client has applied; true on the first poll after a new one, and
   * never before the first update after the hook went on a new socket. The first update after a
   * reconnect moves the count on by the time since the last one.
   */
  private boolean newTick() {
    TickHook h = installHook();
    if (h == null) {
      if (!noHook) return false;
      long now = (System.currentTimeMillis() - startMs) / tickMs;
      if (decided && now <= tick) return false;
      applied = decided ? now - tick : 1L;
      tick = now;
      return true;
    }
    long ticks = h.ticks;
    if (h != hook) {
      hook = h;
      hookTicks = ticks;
      return false;
    }
    if (ticks == hookTicks) return false;
    long now = System.currentTimeMillis();
    applied = ticks - hookTicks;
    tick += loggedOut && updateMs > 0L ? Math.max(1L, (now - updateMs) / tickMs) : applied;
    hookTicks = ticks;
    updateMs = now;
    return true;
  }

  /*
   * The tick hook on the client's socket input, put there at a packet boundary when the input is
   * not wrapped yet (each login opens a new socket); null until then. When this IdleRSC lacks the
   * fields, it says so once and the camper decides every tick length by the clock.
   */
  private TickHook installHook() {
    if (noHook) return null;
    Object stream = controller.mud.packetHandler.getClientStream();
    try {
      if (inStream == null) {
        inStream = field(stream.getClass(), "inStream");
        packetLength = field(stream.getClass(), "incomingPacketLength");
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      noHook = true;
      controller.log("No tick hook: deciding every " + tickMs + " ms", "red");
      return null;
    }
    try {
      Object in = inStream.get(stream);
      if (in instanceof TickHook && ((TickHook) in).stream == stream) return (TickHook) in;
      if (!(in instanceof InputStream)
          || packetLength.getInt(stream) != 0
          || ((InputStream) in).available() != 0) {
        return in == null ? null : hook;
      }
      TickHook h = new TickHook((InputStream) in, stream, packetLength, controller, imageHook, fatigueHook);
      inStream.set(stream, h);
      if (hook != null) loggedOut = true;
      return h;
    } catch (IllegalAccessException | IOException e) {
      // A socket closing under us: try again on the next poll.
      return hook;
    }
  }

  private static Field field(Class<?> type, String name) throws NoSuchFieldException {
    for (Class<?> c = type; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException e) {
        // look in the superclass
      }
    }
    throw new NoSuchFieldException(name);
  }

  /*
   * The client's socket input, watched for server updates. The client reads one packet a frame
   * and handles it before it next asks what is available; so once nothing more is waiting, it has
   * applied every player position update, one a server tick, that came in before.
   */
  private static final class TickHook extends FilterInputStream {
    private static final int OP_PLAYER_COORDS = 191;
    private static final int OP_SLEEP_SCREEN = 117;
    private static final int OP_SLEEP_FATIGUE = 244;
    final Object stream;
    private final Field packetLength;
    private final Controller client;
    private final boolean imageHook, fatigueHook;
    /* sleep images (byte[]) and sleep fatigues (Integer) read, in order, for the script thread */
    private final List<Object> sleeps = new ArrayList<>();
    volatile long ticks;
    private int header = 2;
    private int left;
    private boolean opcodeNext;
    private int updates;
    /* the sleep packet being read: the opcode, then the payload, as the client's packet buffer */
    private byte[] sleepPacket;
    private int sleepRead;

    TickHook(
        InputStream in,
        Object stream,
        Field packetLength,
        Controller client,
        boolean imageHook,
        boolean fatigueHook) {
      super(in);
      this.stream = stream;
      this.packetLength = packetLength;
      this.client = client;
      this.imageHook = imageHook;
      this.fatigueHook = fatigueHook;
    }

    @Override
    public int available() throws IOException {
      int available = super.available();
      boolean boundary;
      try {
        boundary = packetLength.getInt(stream) == 0;
      } catch (IllegalAccessException e) {
        boundary = false;
      }
      // The client asks only between packets with no length read yet: re-sync there.
      if (boundary) {
        header = 2;
        left = 0;
        opcodeNext = false;
        sleepPacket = null;
        if (available == 0 && updates > 0) {
          ticks += updates;
          updates = 0;
        }
      }
      return available;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) see(b);
      return b;
    }

    @Override
    public int read(byte[] data, int offset, int count) throws IOException {
      int n = super.read(data, offset, count);
      for (int i = 0; i < n; i++) see(data[offset + i] & 0xff);
      return n;
    }

    /* Each packet is a 2-byte length counting itself, then the opcode and the rest. */
    private void see(int b) {
      if (header > 0) {
        left = (left << 8) | b;
        if (--header > 0) return;
        left -= 2;
        opcodeNext = true;
        if (left <= 0 || left > 5000) left = 0;
        return;
      }
      if (left <= 0) return;
      if (opcodeNext) {
        opcodeNext = false;
        if (b == OP_PLAYER_COORDS) updates++;
        if (imageHook && b == OP_SLEEP_SCREEN || fatigueHook && b == OP_SLEEP_FATIGUE) {
          sleepPacket = new byte[left];
          sleepRead = 0;
        }
      }
      if (sleepPacket != null) sleepPacket[sleepRead++] = (byte) b;
      if (--left == 0) {
        header = 2;
        if (sleepPacket != null) sleep(sleepPacket);
        sleepPacket = null;
      }
    }

    /*
     * Queues what a sleep packet gives IdleRSC's sleep hooks: the image, then a fatigue of 0 when it
     * came while asleep at 0 already, as after a wrong word, when the server sends no fatigue again;
     * or the sleep fatigue.
     */
    private void sleep(byte[] packet) {
      synchronized (sleeps) {
        if (packet[0] == (byte) OP_SLEEP_SCREEN) {
          sleeps.add(packet);
          if (fatigueHook && client.isSleeping() && client.getFatigueDuringSleep() == 0) {
            sleeps.add(0);
          }
        } else if (packet.length >= 3) {
          sleeps.add(((packet[1] & 0xff) << 8) | (packet[2] & 0xff));
        }
      }
    }

    /*
     * Calls IdleRSC's sleep hooks with what came since the last call, in order: the image hook
     * reads the word, the fatigue hook sends it at 0. Called from the script thread, since sending
     * waits for the client's thread to flush.
     */
    void drainSleeps() {
      List<Object> got;
      synchronized (sleeps) {
        if (sleeps.isEmpty()) return;
        got = new ArrayList<>(sleeps);
        sleeps.clear();
      }
      for (Object o : got) {
        try {
          if (o instanceof byte[]) {
            SleepCallback.sleepHook((byte[]) o, ((byte[]) o).length);
          } else {
            SleepCallback.fatigueHook((Integer) o);
          }
        } catch (RuntimeException e) {
          Main.logError("Sleep hook", e);
        }
      }
    }
  }

  /* The tile the server last put the NPC on; the client draws it walking there. */
  private int serverX(ORSCharacter npc) {
    return controller.convertX(npc.waypointsX[npc.waypointIndexCurrent]);
  }

  private int serverZ(ORSCharacter npc) {
    return controller.convertZ(npc.waypointsZ[npc.waypointIndexCurrent]);
  }

  /* Updates what the camper keeps for each target. */
  private void observe() {
    Map<Integer, ORSCharacter> now = new HashMap<>();
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null) continue;
      Target t = byIndex.get(npc.serverIndex);
      if (t != null && npc.npcId == t.npcId) now.put(t.index, npc);
    }
    Target hunted = heading;
    for (Target t : targets) {
      ORSCharacter npc = now.get(t.index);
      if (npc != null) {
        boolean fighting = controller.isNpcInCombat(npc.serverIndex);
        t.due = fighting ? tick + t.respawn : NOT_DUE;
        t.hasTile = false;
        t.waiting = 0;
        t.seen = true;
        t.seenFighting = fighting;
        t.seenX = serverX(npc);
        t.seenZ = serverZ(npc);
        continue;
      }
      boolean leftNear = false;
      if (t.seen && inView(t.seenX, t.seenZ)) {
        // Fighting, it keeps the rolling due of when it was last seen.
        if (!t.seenFighting) t.due = tick + t.respawn;
        t.waiting = 0;
        leftNear = true;
      } else if (t.seen && !t.seenFighting) {
        t.hasTile = true;
        t.tileX = t.seenX;
        t.tileZ = t.seenZ;
      }
      t.seen = false;
      if (t.hasTile && inView(t.tileX, t.tileZ)) t.hasTile = false;
      boolean atSpawn = tiles(t.x, t.z) <= 1;
      // Someone else killed it out of sight.
      if ((t.due == NOT_DUE || tick - t.due > 1) && (atSpawn && !t.hasTile || boxInView(t))) {
        t.due = tick + t.respawn;
      }
      if (!leftNear && t == hunted && atSpawn) t.waiting += (int) applied;
    }
    visible = now;
  }

  private void act() {
    if (food() != -1) outOfFood = false;
    if (camping && !nearCamp(px, pz)) {
      camping = false;
      heading = null;
      huntTile = null;
    }
    // The client can say so a tick or two after an NPC is fighting on the player's tile.
    if (controller.isInCombat() || opponent() != null) {
      fighting();
      return;
    }
    if (controller.isInBank()) {
      banking();
      return;
    }
    batch = null;
    if (controller.isInOptionMenu()) {
      status = "Opening the bank";
      controller.optionAnswer(0);
      controller.mud.setOptionsMenuShow(false);
      return;
    }
    if (style >= 0 && controller.getFightMode() != style) {
      controller.setFightMode(style);
      return;
    }
    if (eatAt >= 0 && hits() <= eatAt) {
      int food = food();
      if (food != -1) {
        status = "Eating";
        controller.itemCommand(food);
        return;
      }
      if (!outOfFood) {
        controller.log("Out of food", "red");
        outOfFood = true;
      }
    }
    if (wear() || buryBones()) return;
    if (standing) {
      standing = false;
      if (stepAside()) return;
    }
    if (needBank() || route == bankRoute) {
      toBank();
      return;
    }
    if (!camping) {
      if (!follow(campRoute)) return;
      route = null;
      camping = true;
    }
    if (takeLoot(heading)) return;
    hunt(heading);
  }

  private void fighting() {
    if (eatAt >= 0 && hits() <= eatAt) {
      status = "Retreating";
      controller.walkToAsync(px, pz, 0);
      return;
    }
    ORSCharacter opponent = opponent();
    if (opponent != null
        && !isTarget(opponent)
        && !(opponent.serverIndex == blockerIndex && contains(block, opponent.npcId))) {
      int[] to;
      if (route != null && stepIndex < route.length) {
        to = new int[] {route[stepIndex].x, route[stepIndex].z};
      } else if (camping && huntTile != null) {
        to = huntTile;
      } else {
        to = new int[] {px, pz};
      }
      if (!reaches(to[0], to[1])) to = new int[] {px, pz};
      status = "Breaking off";
      controller.walkToAsync(to[0], to[1], 0);
      return;
    }
    status = "Fighting";
  }

  /* The NPC fighting on the player's tile, the lowest server index first; null when none is. */
  private ORSCharacter opponent() {
    ORSCharacter best = null;
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || serverX(npc) != px || serverZ(npc) != pz) continue;
      if (best != null && npc.serverIndex >= best.serverIndex) continue;
      if (controller.isNpcInCombat(npc.serverIndex)) best = npc;
    }
    return best;
  }

  private boolean isTarget(ORSCharacter npc) {
    Target t = byIndex.get(npc.serverIndex);
    return t != null && t.npcId == npc.npcId;
  }

  private boolean wear() {
    for (Carry c : carries) {
      if (!c.wear || worn(c.id)) continue;
      int slot = unwornSlot(c.id);
      if (slot == -1) continue;
      status = "Equipping";
      controller.equipItem(slot);
      return true;
    }
    return false;
  }

  /* Worn in any slot; on Coleslaw a worn item leaves the inventory. */
  private boolean worn(int id) {
    if (!controller.isAuthentic()) return controller.isItemIdEquipped(id);
    return wornCount(id) > 0;
  }

  private int wornCount(int id) {
    if (!controller.isAuthentic()) return 0;
    int[] ids = controller.getInventoryItemIds();
    int count = 0;
    for (int slot = 0; slot < ids.length; slot++) {
      if (ids[slot] == id && controller.isEquipped(slot)) count++;
    }
    return count;
  }

  private int unwornSlot(int id) {
    int[] ids = controller.getInventoryItemIds();
    for (int slot = 0; slot < ids.length; slot++) {
      if (ids[slot] == id && !controller.isEquipped(slot)) return slot;
    }
    return -1;
  }

  private boolean buryBones() {
    for (int id : bury) {
      if (!controller.isItemInInventory(id)) continue;
      status = "Burying";
      controller.itemCommand(id);
      return true;
    }
    return false;
  }

  /* Walks to the first neighbouring tile the client's walking reaches; false when there is none. */
  private boolean stepAside() {
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if ((dx != 0 || dz != 0) && reaches(px + dx, pz + dz)) {
          status = "Stepping aside";
          walk(px + dx, pz + dz);
          return true;
        }
      }
    }
    return false;
  }

  private boolean needBank() {
    for (Carry c : carries) {
      if (carried(c) < c.min) return true;
    }
    return controller.getInventoryItemCount() >= INVENTORY_SIZE && !deposits().isEmpty();
  }

  /* {id, amount} of everything not worn that no item line names. */
  private List<int[]> deposits() {
    List<int[]> out = new ArrayList<>();
    Set<Integer> done = new HashSet<>();
    for (int id : controller.getInventoryItemIds()) {
      if (id == -1 || carry(id) != null || !done.add(id)) continue;
      int amount = controller.getInventoryItemCount(id) - wornCount(id);
      if (amount > 0) out.add(new int[] {id, amount});
    }
    return out;
  }

  private void toBank() {
    boolean near = tiles(bankX, bankZ) <= BANK_TILES;
    if (near) route = null;
    boolean ended = route == bankRoute && stepIndex >= bankRoute.length;
    if (!near && !ended && !follow(bankRoute)) return;
    ORSCharacter banker = nearestBanker();
    if (banker != null) {
      status = "Talking to a banker";
      controller.talkToNpc(banker.serverIndex);
    } else if (px != bankX || pz != bankZ) {
      status = "Walking to the bank";
      walk(bankX, bankZ);
    }
  }

  private ORSCharacter nearestBanker() {
    ORSCharacter best = null;
    int bestDist = Integer.MAX_VALUE;
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || !contains(BANKERS, npc.npcId)) continue;
      int dist = tiles(serverX(npc), serverZ(npc));
      if (best == null || dist < bestDist || (dist == bestDist && before(npc, best))) {
        best = npc;
        bestDist = dist;
      }
    }
    return best;
  }

  /* Lower x, then z, then id. */
  private boolean before(ORSCharacter a, ORSCharacter b) {
    if (serverX(a) != serverX(b)) return serverX(a) < serverX(b);
    if (serverZ(a) != serverZ(b)) return serverZ(a) < serverZ(b);
    return a.npcId < b.npcId;
  }

  private void banking() {
    if (route == bankRoute) route = null;
    status = "Banking";
    List<int[]> deposits = deposits();
    if (!deposits.isEmpty()) {
      if (!stillToDo("deposits")) return;
      for (int[] d : deposits) controller.depositItem(d[0], d[1]);
      return;
    }
    int[] amounts = withdrawals();
    if (amounts == null) return;
    boolean any = false;
    for (int amount : amounts) any |= amount > 0;
    if (any) {
      if (!stillToDo("withdrawals")) return;
      for (int i = 0; i < amounts.length; i++) {
        if (amounts[i] > 0) controller.withdrawItem_apos(carries.get(i).id, amounts[i]);
      }
      return;
    }
    batch = null;
    controller.mud.setShowDialogBank(false);
    send(OP_BANK_CLOSE);
    controller.mud.packetHandler.getClientStream().finishPacket();
  }

  /* False, having stopped, when the batch was first sent 3 ticks ago. */
  private boolean stillToDo(String kind) {
    if (!kind.equals(batch)) {
      batch = kind;
      batchTick = tick;
    } else if (tick - batchTick >= BANK_TICKS) {
      stop("The bank did not take " + kind);
      return false;
    }
    return true;
  }

  /*
   * How many of each item to withdraw: every min first, then up to each target in the recipe's
   * order as far as the free slots go. Null, having stopped, when a min cannot be met.
   */
  private int[] withdrawals() {
    int free = INVENTORY_SIZE - controller.getInventoryItemCount();
    int[] amounts = new int[carries.size()];
    int[] banked = new int[carries.size()];
    for (int i = 0; i < amounts.length; i++) {
      Carry c = carries.get(i);
      banked[i] = Math.max(0, controller.getBankItemCount(c.id));
      int have = carried(c);
      if (have >= c.min) continue;
      String name = controller.getItemName(c.id);
      if (have + banked[i] < c.min) {
        stop(
            "Out of "
                + name
                + ": "
                + have
                + " carried, "
                + banked[i]
                + " in the bank, min "
                + c.min);
        return null;
      }
      int need = c.min - have;
      int slots = slots(c.id, need);
      if (slots > free) {
        stop("No room for " + name);
        return null;
      }
      free -= slots;
      amounts[i] = need;
    }
    for (int i = 0; i < amounts.length; i++) {
      Carry c = carries.get(i);
      int want = Math.min(c.target - carried(c) - amounts[i], banked[i] - amounts[i]);
      if (want <= 0) continue;
      if (controller.isItemStackable(c.id)) {
        int slots = amounts[i] > 0 ? 0 : slots(c.id, want);
        if (slots > free) continue;
        free -= slots;
      } else {
        want = Math.min(want, free);
        free -= want;
      }
      amounts[i] += want;
    }
    return amounts;
  }

  /* The free slots that many of the item takes: a stack one, or none when one is carried. */
  private int slots(int id, int amount) {
    if (!controller.isItemStackable(id)) return amount;
    return controller.isItemInInventory(id) ? 0 : 1;
  }

  /* Follows the route from wherever the player is; true once it is at its end. */
  private boolean follow(Step[] steps) {
    if (route != steps) route = null;
    if (route != null && stepIndex < steps.length) {
      advance();
      if (stepIndex < steps.length && far(steps[stepIndex]) && !resume(steps, false)) {
        return false;
      }
    }
    if (route == null && !resume(steps, true)) return false;
    if (stepIndex >= steps.length) return true;
    take(steps[stepIndex]);
    return false;
  }

  /*
   * Resumes the route from its nearest step; with none, walks the long-distance walker toward it.
   * False while the route is not under way.
   */
  private boolean resume(Step[] steps, boolean starting) {
    int start = nearestStep(steps);
    if (start < 0) {
      route = null;
      farWalk(steps);
      return false;
    }
    farRoute = null;
    farTried.clear();
    route = steps;
    stepIndex = start;
    if (starting || start > furthest) {
      furthest = start;
      furthestTick = tick;
      stallLogTick = tick;
    }
    controller.log("Route " + routeName() + ": from step " + (start + 1) + " of " + steps.length);
    advance();
    return true;
  }

  private boolean far(Step s) {
    return floorTiles(s.x, s.z) > ROUTE_REACH;
  }

  private void advance() {
    while (stepIndex < route.length && done(route[stepIndex])) stepIndex++;
    if (stepIndex > furthest) {
      furthest = stepIndex;
      furthestTick = tick;
      stallLogTick = tick;
    }
    if (stepIndex >= route.length) controller.log("Route " + routeName() + ": done");
  }

  /*
   * Walks the long-distance walker toward the step or landing of the route nearest the player on
   * its floor that it has a path to, trying the next nearest when it has none. One path search a
   * tick: the current tile's, or else the next untried one's.
   */
  private void farWalk(Step[] steps) {
    if (farRoute != steps) {
      farRoute = steps;
      farTile = null;
      farTried.clear();
    }
    status = "Walking to the route";
    if (farTile != null) {
      if (!webStep(farTile)) farTile = null;
      return;
    }
    List<int[]> tiles = farTiles(steps);
    if (tiles.isEmpty()) {
      stop("No way to the route");
      return;
    }
    int[] t = tiles.get(0);
    farTried.add(key(t[0], t[1]));
    if (!webStep(t)) return;
    farTile = t;
    controller.log(
        "Route "
            + (steps == bankRoute ? "bank" : "camp")
            + ": too far to resume, walking to "
            + t[0]
            + ","
            + t[1]);
  }

  /*
   * One tick of the web walker's way to the tile: its path from the node nearest the player, found
   * anew every tick, then the walk to the next node, or the walker's own handler for a labelled
   * link. False when it has no path. Without its path search, its own walkTowards, which holds the
   * tick until it arrives.
   */
  private boolean webStep(int[] tile) {
    WebWalker walker = controller.getWebWalker();
    if (webPaths && pathSearch == null) {
      try {
        Field graph = WebWalker.class.getDeclaredField("graph");
        graph.setAccessible(true);
        webGraph = graph.get(walker);
        pathSearch =
            WebWalker.class.getDeclaredMethod("dijkstraPathfinding", WebwalkNode.class, Set.class);
        pathSearch.setAccessible(true);
        labelHandler =
            WebWalker.class.getDeclaredMethod("executeCustomLabelFunction", String.class);
        labelHandler.setAccessible(true);
      } catch (ReflectiveOperationException | RuntimeException e) {
        webPaths = false;
        controller.log("No web walker path search: walking with walkTowards", "red");
      }
    }
    if (!webPaths) return controller.walkTowards(tile[0], tile[1]);
    try {
      WebwalkGraph graph = (WebwalkGraph) webGraph;
      WebwalkNode start = graph.findClosestNode(px, pz);
      WebwalkNode goal = graph.findClosestNode(tile[0], tile[1]);
      if (start == null || goal == null) return false;
      @SuppressWarnings("unchecked")
      List<WebwalkNode> path =
          (List<WebwalkNode>) pathSearch.invoke(walker, start, Collections.singleton(goal));
      if (path.isEmpty()) return false;
      if (goal.getX() != tile[0] || goal.getY() != tile[1]) {
        path.add(new WebwalkNode(tile[0], tile[1]));
      }
      if (path.size() > 1) {
        WebwalkEdge edge = graph.getEdge(path.get(0), path.get(1));
        if (edge != null && edge.getLabel().isPresent()) {
          labelHandler.invoke(walker, edge.getLabel().get());
          return true;
        }
      }
      WebwalkNode next = path.get(Math.min(1, path.size() - 1));
      int x = next.getX();
      int z = next.getY();
      // A node beyond the loaded map is walked toward along the line to it.
      for (int i = 1; !loaded(x, z) && i < 64; i++) {
        x = next.getX() + (px - next.getX()) * i / 64;
        z = next.getY() + (pz - next.getY()) * i / 64;
      }
      if ((px != x || pz != z) && loaded(x, z)) controller.walkToAsync(x, z, 0);
      return true;
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("web walker: " + e, e);
    }
  }

  /* The route's untried steps and landings on the player's floor, nearest first. */
  private List<int[]> farTiles(Step[] steps) {
    List<int[]> out = new ArrayList<>();
    for (Step s : steps) {
      addFarTile(out, s.x, s.z);
      if (s.lands()) addFarTile(out, s.landX, s.landZ);
    }
    out.sort((a, b) -> Integer.compare(tiles(a[0], a[1]), tiles(b[0], b[1])));
    return out;
  }

  private void addFarTile(List<int[]> out, int x, int z) {
    if (floorTiles(x, z) == Integer.MAX_VALUE || farTried.contains(key(x, z))) return;
    for (int[] t : out) {
      if (t[0] == x && t[1] == z) return;
    }
    out.add(new int[] {x, z});
  }

  private static long key(int x, int z) {
    return ((long) x << 32) | (z & 0xffffffffL);
  }

  /*
   * The step nearest the player on its floor, within 30 tiles, that the client's walking reaches;
   * a landing stands for the step after its own. The later wins a tie, so a step the player is
   * already past is not done again. -1 when there is none.
   */
  private int nearestStep(Step[] steps) {
    int best = -1;
    int bestDist = ROUTE_REACH;
    for (int i = 0; i < steps.length; i++) {
      Step s = steps[i];
      int dist = floorTiles(s.x, s.z);
      if (dist <= bestDist && sent(s) && usable(s)) {
        best = i;
        bestDist = dist;
      }
      if (!s.lands()) continue;
      dist = floorTiles(s.landX, s.landZ);
      if (dist <= bestDist && reaches(s.landX, s.landZ)) {
        best = i + 1;
        bestDist = dist;
      }
    }
    return best;
  }

  /*
   * The client has been sent the step's object or wall; a walk step has none. The server sends
   * IdleRSC, a custom client, scenery only within 2 regions of 8 tiles, nearer than a route
   * resumes from
   * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1093
   */
  private boolean sent(Step s) {
    switch (s.kind) {
      case WALK:
        return true;
      case GATE:
      case OBJ:
      case USEOBJ:
        return objectIdAt(s.x, s.z) != -1;
      default:
        return wallIdAt(s.x, s.z, s.dir) != -1;
    }
  }

  /* The client's walking reaches the step's tile, or a tile its object or wall is used from. */
  private boolean usable(Step s) {
    if (s.kind == WALK) return reaches(s.x, s.z);
    int[] a = useArea(s);
    return reachesArea(a[0], a[1], a[2], a[3], a[4] == 1);
  }

  /*
   * The area the stock client walks to for the step's object or wall, by the object's size, turn
   * and type and the wall's side: min x, min z, max x, max z, and 1 when it walks beside the area.
   */
  private int[] useArea(Step s) {
    int x = s.x;
    int z = s.z;
    if (s.kind == DOOR || s.kind == WALL || s.kind == CUT || s.kind == USEWALL) {
      if (s.dir == 0) return new int[] {x, z - 1, x, z, 0};
      if (s.dir == 1) return new int[] {x - 1, z, x, z, 0};
      return new int[] {x, z, x, z, 1};
    }
    int count = controller.getObjectsCount();
    int[] ids = controller.getObjectsIds();
    int[] xs = controller.getObjectsX();
    int[] zs = controller.getObjectsZ();
    int[] dirs = (int[]) controller.getMudClientValue("gameObjectInstanceDir");
    int localX = controller.removeOffsetX(x);
    int localZ = controller.removeOffsetZ(z);
    for (int i = 0; i < count; i++) {
      if (xs[i] != localX || zs[i] != localZ) continue;
      GameObjectDef def = EntityHandler.getObjectDef(ids[i]);
      int dir = dirs[i];
      int w = dir == 0 || dir == 4 ? def.getWidth() : def.getHeight();
      int h = dir == 0 || dir == 4 ? def.getHeight() : def.getWidth();
      if (def.getType() != 2 && def.getType() != 3) {
        return new int[] {x, z, x + w - 1, z + h - 1, 1};
      }
      if (dir == 0) {
        w++;
        x--;
      }
      if (dir == 2) h++;
      if (dir == 6) {
        z--;
        h++;
      }
      if (dir == 4) w++;
      return new int[] {x, z, x + w - 1, z + h - 1, 0};
    }
    return new int[] {x, z, x, z, 1};
  }

  /*
   * Before a click or use on the step's object or wall: walks round the block NPCs to where it is
   * used from, or attacks the one in the way; true when it did, and the click waits.
   */
  private boolean approach(Step s) {
    int[] a = useArea(s);
    return wayRound(a[0], a[1], a[2], a[3], a[4] == 1, false);
  }

  /* The last door, gate or cut step before the current one; -1 if there is none. */
  private int doorBehind() {
    for (int i = stepIndex - 1; i >= 0; i--) {
      int kind = route[i].kind;
      if (kind == DOOR || kind == GATE || kind == CUT) return i;
    }
    return -1;
  }

  private boolean done(Step s) {
    switch (s.kind) {
      case WALK:
        return tiles(s.x, s.z) <= STEP_TILES;
      case DOOR:
      case CUT:
        return wallIdAt(s.x, s.z, s.dir) != s.closedId;
      case GATE:
        return objectIdAt(s.x, s.z) != s.closedId;
      default:
        if (s.landZ / FLOOR != s.z / FLOOR) return tiles(s.landX, s.landZ) <= 1;
        return px == s.landX && pz == s.landZ;
    }
  }

  private void take(Step s) {
    // Back to the earliest shut door, gate or web behind, all in this tick.
    while (!usable(s)) {
      int door = doorBehind();
      if (door == -1 || done(route[door])) break;
      stepIndex = door;
      s = route[door];
    }
    if (tick - furthestTick >= STEP_LOG_TICKS && tick - stallLogTick >= STEP_LOG_TICKS) {
      stallLogTick = tick;
      controller.log(
          "Route "
              + routeName()
              + " step "
              + (stepIndex + 1)
              + " ("
              + s.text
              + ") for "
              + (tick - furthestTick)
              + " ticks");
    }
    if (s.kind == WALK) {
      status = "Walking to " + s.x + "," + s.z;
      walk(s.x, s.z);
      return;
    }
    if (s.item != 0 && !carries(s.item)) {
      stop(
          "Route "
              + routeName()
              + " step "
              + (stepIndex + 1)
              + " needs "
              + controller.getItemName(s.item));
      return;
    }
    status = "Crossing at " + s.x + "," + s.z;
    // On Coleslaw a worn item is carried but has no inventory slot to use it from: it waits.
    if (there(s)
        && (s.item == 0 || controller.getInventoryItemSlotIndex(s.item) != -1)
        && !approach(s)) {
      click(s);
    }
  }

  private String routeName() {
    return route == bankRoute ? "bank" : "camp";
  }

  /* The object or wall the step clicks is in view. */
  private boolean there(Step s) {
    switch (s.kind) {
      case DOOR:
      case CUT:
        return wallIdAt(s.x, s.z, s.dir) == s.closedId;
      case GATE:
        return objectIdAt(s.x, s.z) == s.closedId;
      case WALL:
      case USEWALL:
        return wallIdAt(s.x, s.z, s.dir) != -1;
      default:
        return objectIdAt(s.x, s.z) != -1;
    }
  }

  private void click(Step s) {
    switch (s.kind) {
      case DOOR:
        clickWall(s.x, s.z, s.dir, 1);
        break;
      case WALL:
        clickWall(s.x, s.z, s.dir, s.cmd);
        break;
      case CUT:
        if (s.item == 0) {
          clickWall(s.x, s.z, s.dir, 1);
        } else {
          useOnWall(s.x, s.z, s.dir, s.item);
        }
        break;
      case USEWALL:
        useOnWall(s.x, s.z, s.dir, s.item);
        break;
      case USEOBJ:
        controller.useItemIdOnObject(s.x, s.z, s.item);
        break;
      default:
        if (s.cmd == 2) {
          controller.atObject2(s.x, s.z);
        } else {
          controller.atObject(s.x, s.z);
        }
    }
  }

  private void clickWall(int x, int z, int dir, int cmd) {
    if (controller.getWallObjectDirectionAtCoord(x, z) == dir) {
      if (cmd == 2) {
        controller.atWallObject2(x, z);
      } else {
        controller.atWallObject(x, z);
      }
      return;
    }
    wallPacket(cmd == 2 ? 127 : 14, x, z, dir, -1);
  }

  private void useOnWall(int x, int z, int dir, int itemId) {
    int slot = controller.getInventoryItemSlotIndex(itemId);
    if (controller.getWallObjectDirectionAtCoord(x, z) == dir) {
      controller.useItemOnWall(x, z, slot);
    } else {
      wallPacket(161, x, z, dir, slot);
    }
  }

  /*
   * Another wall object on this tile comes first in the client's list, so the controller would act
   * on that one: walk beside ours and send the packet for it, with the slot used when not -1.
   */
  private void wallPacket(int opcode, int x, int z, int dir, int slot) {
    int sideX = x;
    int sideZ = z;
    if (dir == 0 && pz < z) sideZ = z - 1;
    if (dir == 1 && px < x) sideX = x - 1;
    controller.walkToAsync(sideX, sideZ, 0);
    send(opcode);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(x);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(z);
    controller.mud.packetHandler.getClientStream().bufferBits.putByte(dir);
    if (slot != -1) controller.mud.packetHandler.getClientStream().bufferBits.putShort(slot);
    controller.mud.packetHandler.getClientStream().finishPacket();
  }

  /* Starts a packet once the client has sent the last one. */
  private void send(int opcode) {
    while (controller.mud.packetHandler.getClientStream().hasFinishedPackets()) {
      controller.sleep(1);
    }
    controller.mud.packetHandler.getClientStream().newPacket(opcode);
  }

  private int wallIdAt(int x, int z, int dir) {
    int count = controller.getWallObjectsCount();
    int[] ids = controller.getWallObjectIds();
    int[] xs = controller.getWallObjectsX();
    int[] zs = controller.getWallObjectsZ();
    int[] dirs = controller.getWallObjectsDirections();
    int localX = controller.removeOffsetX(x);
    int localZ = controller.removeOffsetZ(z);
    for (int i = 0; i < count; i++) {
      if (xs[i] == localX && zs[i] == localZ && dirs[i] == dir) return ids[i];
    }
    return -1;
  }

  private int objectIdAt(int x, int z) {
    int count = controller.getObjectsCount();
    int[] ids = controller.getObjectsIds();
    int[] xs = controller.getObjectsX();
    int[] zs = controller.getObjectsZ();
    int localX = controller.removeOffsetX(x);
    int localZ = controller.removeOffsetZ(z);
    for (int i = 0; i < count; i++) {
      if (xs[i] == localX && zs[i] == localZ) return ids[i];
    }
    return -1;
  }

  private boolean takeLoot(Target last) {
    if (loot.length == 0 && bury.length == 0) return false;
    if (last != null && dueAtSpawn(last)) return false;
    int count = controller.getGroundItemsCount();
    int[] ids = controller.getGroundItems();
    int[] xs = controller.getGroundItemsX();
    int[] zs = controller.getGroundItemsY();
    int best = -1;
    int bestDist = Integer.MAX_VALUE;
    int[] to = null;
    for (int i = 0; i < count; i++) {
      if (!contains(loot, ids[i]) && !contains(bury, ids[i])) continue;
      if (!roomFor(ids[i])) continue;
      int dist = tiles(xs[i], zs[i]);
      boolean own = xs[i] == px && zs[i] == pz;
      if (!own && (dist > LOOT_TILES || !inCamp(xs[i], zs[i]))) continue;
      if (dist > bestDist) continue;
      if (dist == bestDist
          && (xs[i] > xs[best]
              || (xs[i] == xs[best]
                  && (zs[i] > zs[best] || (zs[i] == zs[best] && ids[i] >= ids[best]))))) {
        continue;
      }
      int[] tile = own ? new int[] {px, pz} : lootTile(xs[i], zs[i]);
      if (tile == null) continue;
      best = i;
      bestDist = dist;
      to = tile;
    }
    if (best == -1) return false;
    status = "Looting";
    if (px != to[0] || pz != to[1]) controller.walkToAsync(to[0], to[1], 0);
    send(OP_TAKE);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(xs[best]);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(zs[best]);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(ids[best]);
    controller.mud.packetHandler.getClientStream().finishPacket();
    return true;
  }

  /*
   * As the stock client walks for a ground item: its tile when the client's walking reaches it,
   * else the tile beside it in x or z, with no wall between, that the client's search reaches
   * first; null when neither.
   */
  private int[] lootTile(int x, int z) {
    if (reaches(x, z)) return new int[] {x, z};
    return reachedTile(x, z, x, z, true);
  }

  /* Due 0 to 5 ticks from now, with the player within a tile of its spawn. */
  private boolean dueAtSpawn(Target t) {
    long left = t.due - tick;
    return t.due != NOT_DUE && left >= 0 && left <= SOON_TICKS && tiles(t.x, t.z) <= 1;
  }

  /* The NPC of a target that can be attacked: in view, not fighting, in the camp and reached. */
  private ORSCharacter live(Target t) {
    ORSCharacter npc = visible.get(t.index);
    if (npc == null) return null;
    int x = serverX(npc);
    int z = serverZ(npc);
    if (!inCamp(x, z) || controller.isNpcInCombat(npc.serverIndex) || !reaches(x, z)) return null;
    return npc;
  }

  /* Ticks until the target can be fought, with tiles as the walking time. */
  private long score(Target t) {
    ORSCharacter npc = live(t);
    if (npc != null) return tiles(serverX(npc), serverZ(npc));
    if (t.due != NOT_DUE) {
      long walk = tiles(t.x, t.z);
      long wait = Math.max(0L, t.due - tick);
      return Math.max(walk, wait) + (walk > wait + WALK_SLACK_TICKS ? UNSURE_TICKS : 0);
    }
    return (t.hasTile ? tiles(t.tileX, t.tileZ) : tiles(t.x, t.z)) + UNSURE_TICKS;
  }

  /*
   * The target not skipped that scores lowest, the first listed on a tie; but the hunted one while
   * it scores less than 2 worse, or while it is due soon with the player at its spawn.
   */
  private Target choose(Target last) {
    Target best = null;
    long bestScore = Long.MAX_VALUE;
    long lastScore = Long.MAX_VALUE;
    for (Target t : targets) {
      if (tick < t.skipUntil) continue;
      long score = score(t);
      if (t == last) lastScore = score;
      if (score < bestScore) {
        best = t;
        bestScore = score;
      }
    }
    if (last == null || last == best || tick < last.skipUntil) return best;
    return dueAtSpawn(last) || lastScore - bestScore < KEEP_TICKS ? last : best;
  }

  /*
   * The corner of the target's wander box farthest from its spawn that the client's walking
   * reaches, the first in the order below on a tie; null when it reaches none.
   */
  private int[] farCorner(Target t) {
    int[][] corners = {{t.minX, t.minZ}, {t.maxX, t.minZ}, {t.minX, t.maxZ}, {t.maxX, t.maxZ}};
    int[] best = null;
    int bestDist = -1;
    for (int[] c : corners) {
      int dist = Math.max(Math.abs(c[0] - t.x), Math.abs(c[1] - t.z));
      if (dist > bestDist && reaches(c[0], c[1])) {
        best = c;
        bestDist = dist;
      }
    }
    return best;
  }

  private void hunt(Target last) {
    Target best;
    ORSCharacter npc = null;
    int[] at = null;
    int[] tile = null;
    boolean planned = true;
    while (true) {
      best = choose(last);
      if (best == null) break;
      npc = live(best);
      if (npc != null) break;
      at = new int[] {best.x, best.z};
      // Alive somewhere in its box, out of view: wait at a corner until it is seen.
      if (best.waiting > best.respawn) {
        int[] corner = farCorner(best);
        if (corner != null) at = corner;
      } else if (best.due == NOT_DUE && best.hasTile) {
        at = new int[] {best.tileX, best.tileZ};
      }
      if (at[0] != best.x || at[1] != best.z) {
        tile = at;
        planned = reaches(at[0], at[1]);
        break;
      }
      tile = towardSpawn(best);
      if (tile != null) break;
      best.skipUntil = tick + SKIP_TICKS;
      controller.log(
          "No path to #"
              + best.index
              + "'s spawn at "
              + best.x
              + ","
              + best.z
              + "; skipping it for "
              + SKIP_TICKS
              + " ticks",
          "red");
    }
    heading = best;
    if (best == null) {
      huntTile = null;
      status = "Waiting";
      return;
    }
    if (npc != null) {
      int x = serverX(npc);
      int z = serverZ(npc);
      huntTile = new int[] {x, z};
      status = "Attacking #" + best.index;
      if (tiles(x, z) > NEAR_TILES) {
        walk(x, z);
      } else {
        controller.walktoNPCAsync(best.index);
        sleepFirst();
        attackBare(best.index);
      }
      return;
    }
    huntTile = at;
    status = "Waiting for #" + best.index;
    // The attack on a block NPC in the way is this tick's attack.
    if ((px != tile[0] || pz != tile[1]) && planned && walk(tile[0], tile[1])) return;
    if (visible.containsKey(best.index)) return;
    // The bare attack after the walk: a walk cancels an attack sent before it, not after it.
    preAttackTick = tick;
    sleepFirst();
    attackBare(best.index);
  }

  /*
   * Right before an attack on a target: the sleeping bag, with fatigue at the recipe's level, since
   * a fully fatigued fight gains no experience
   * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L1735
   * After the walk: an item command keeps the walk but drops an attack sent before it
   * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L84-L88
   */
  private void sleepFirst() {
    if (sleepAt == -1 || controller.isSleeping() || controller.getFatigue() < sleepAt) return;
    // Without the tick hook nothing stands in for a missing sleep hook: nothing would wake it.
    if (noHook && (imageHook || fatigueHook)) {
      if (!noSleepLogged) controller.log("No sleep hook: not sleeping", "red");
      noSleepLogged = true;
      return;
    }
    if (controller.isItemInInventory(SLEEPING_BAG)) controller.itemCommand(SLEEPING_BAG);
  }

  /* An attack with no walk of the client's own before it. */
  private void attackBare(int serverIndex) {
    send(OP_ATTACK_NPC);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(serverIndex);
    controller.mud.packetHandler.getClientStream().finishPacket();
  }

  /*
   * The tile to walk to for the spawn: the spawn itself, or beyond the loaded area the tile on the
   * line from it to the player nearest it that is loaded, in the camp and reached. Null when the
   * spawn cannot be reached.
   */
  private int[] towardSpawn(Target t) {
    if (loaded(t.x, t.z)) return reaches(t.x, t.z) ? new int[] {t.x, t.z} : null;
    int n = tiles(t.x, t.z);
    for (int i = 1; i < n; i++) {
      int x = t.x + (px - t.x) * i / n;
      int z = t.z + (pz - t.z) * i / n;
      if (loaded(x, z) && inCamp(x, z) && reaches(x, z)) return new int[] {x, z};
    }
    return null;
  }

  /* The client's walking reaches the tile; a tile outside the loaded area is not reached. */
  private boolean reaches(int x, int z) {
    return reachesArea(x, z, x, z, false);
  }

  /* The client's walking reaches a tile of the area, or its border when the flag says so. */
  private boolean reachesArea(int x1, int z1, int x2, int z2, boolean border) {
    if (px >= x1 && px <= x2 && pz >= z1 && pz <= z2) return true;
    return reachedTile(x1, z1, x2, z2, border) != null;
  }

  /* The tile the client's walking to the area, or its border when the flag says so, ends on. */
  private int[] reachedTile(int x1, int z1, int x2, int z2, boolean border) {
    if (!loaded(x1, z1) || !loaded(x2, z2)) return null;
    int baseX = controller.mud.getMidRegionBaseX();
    int baseZ = controller.mud.getMidRegionBaseZ();
    int[] pathX = new int[8000];
    int[] pathZ = new int[8000];
    try {
      int count =
          controller
              .mud
              .getWorld()
              .findPath(
                  pathX,
                  pathZ,
                  px - baseX,
                  pz - baseZ,
                  x1 - baseX,
                  x2 - baseX,
                  z1 - baseZ,
                  z2 - baseZ,
                  border);
      return count < 1 ? null : new int[] {pathX[0] + baseX, pathZ[0] + baseZ};
    } catch (RuntimeException e) {
      // a region load under way: no path this tick
      return null;
    }
  }

  private boolean loaded(int x, int z) {
    int localX = x - controller.mud.getMidRegionBaseX();
    int localZ = z - controller.mud.getMidRegionBaseZ();
    return localX >= 0 && localX < 96 && localZ >= 0 && localZ < 96;
  }

  private Carry carry(int id) {
    for (Carry c : carries) {
      if (c.id == id) return c;
    }
    return null;
  }

  private boolean carries(int id) {
    return controller.isItemInInventory(id)
        || (!controller.isAuthentic() && controller.isItemIdEquipped(id));
  }

  /* On Coleslaw a worn item leaves the inventory; on authentic worlds it stays there. */
  private int carried(Carry c) {
    int count = controller.getInventoryItemCount(c.id);
    if (!controller.isAuthentic() && controller.isItemIdEquipped(c.id)) count++;
    return count;
  }

  private boolean roomFor(int id) {
    return controller.getInventoryItemCount() < INVENTORY_SIZE
        || (controller.isItemStackable(id) && controller.isItemInInventory(id));
  }

  private int food() {
    for (int id : foods) {
      if (controller.isItemInInventory(id)) return id;
    }
    return -1;
  }

  private int hits() {
    return controller.getCurrentStat(HITS);
  }

  private boolean boxInView(Target t) {
    return inView(t.minX, t.minZ)
        && inView(t.minX, t.maxZ)
        && inView(t.maxX, t.minZ)
        && inView(t.maxX, t.maxZ);
  }

  private boolean inView(int x, int z) {
    int dx = x - px;
    int dz = z - pz;
    return dx * dx + dz * dz <= VIEW_SQ;
  }

  private boolean inCamp(int x, int z) {
    return x >= campMinX && x <= campMaxX && z >= campMinZ && z <= campMaxZ;
  }

  private boolean nearCamp(int x, int z) {
    return x >= campMinX - 1 && x <= campMaxX + 1 && z >= campMinZ - 1 && z <= campMaxZ + 1;
  }

  /*
   * Walks to the tile round the block NPCs in view, or, with no way round them, attacks the one in
   * the way; else walks with the client's walking. True when it attacked.
   */
  private boolean walk(int x, int z) {
    if (wayRound(x, z, x, z, false, true)) return attackedBlocker;
    controller.walkToAsync(x, z, 0);
    return false;
  }

  /*
   * Finds the path to the area, or beside it, as the client does, with the tile of each block NPC
   * in the search box counted as blocked, and sends it as the client sends a walk. With no way round
   * them, attacks the block NPC not fighting on the first tile of the path the client's own walking
   * takes. False, sending nothing, when no block NPC is in the box, when there is no way round and
   * no NPC to attack, or, before a click (walk false), when the player is already there.
   */
  private boolean wayRound(int x1, int z1, int x2, int z2, boolean beside, boolean walk) {
    attackedBlocker = false;
    if (block.length == 0 || !loaded(x1, z1) || !loaded(x2, z2)) return false;
    int baseX = controller.mud.getMidRegionBaseX();
    int baseZ = controller.mud.getMidRegionBaseZ();
    int[][] collision = controller.mud.getWorld().collisionFlags;
    int minX = Math.max(0, Math.min(px, x1) - SEARCH_TILES - baseX);
    int minZ = Math.max(0, Math.min(pz, z1) - SEARCH_TILES - baseZ);
    int w = Math.min(95, Math.max(px, x2) + SEARCH_TILES - baseX) - minX + 1;
    int h = Math.min(95, Math.max(pz, z2) + SEARCH_TILES - baseZ) - minZ + 1;
    int[][] plain = new int[w][h];
    int[][] flags = new int[w][h];
    for (int i = 0; i < w; i++) {
      for (int j = 0; j < h; j++) plain[i][j] = flags[i][j] = collision[minX + i][minZ + j];
    }
    int startX = px - baseX - minX;
    int startZ = pz - baseZ - minZ;
    int destX1 = x1 - baseX - minX;
    int destZ1 = z1 - baseZ - minZ;
    int destX2 = x2 - baseX - minX;
    int destZ2 = z2 - baseZ - minZ;
    boolean near = false;
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || !contains(block, npc.npcId)) continue;
      int nx = serverX(npc) - baseX - minX;
      int nz = serverZ(npc) - baseZ - minZ;
      if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
      if (nx == startX && nz == startZ) continue;
      // A walk to an NPC's tile walks to that NPC.
      if (walk && nx == destX1 && nz == destZ1) continue;
      flags[nx][nz] |= FULL_BLOCK;
      near = true;
    }
    if (!near) return false;
    int[] path = findPath(flags, startX, startZ, destX1, destZ1, destX2, destZ2, beside);
    if (path == null) {
      int[] own = findPath(plain, startX, startZ, destX1, destZ1, destX2, destZ2, beside);
      if (own == null || own[0] == startX && own[1] == startZ) return false;
      int index = blockerOn(px + Integer.signum(own[0] - startX), pz + Integer.signum(own[1] - startZ));
      if (index == -1) return false;
      attackBare(index);
      attackedBlocker = true;
      blockerIndex = index;
      return true;
    }
    if (path.length == 2 && path[0] == startX && path[1] == startZ) return false;
    // The turns from the first to the tile, at most 25 after the first, as the client sends them.
    int firstX = path[0] + minX + baseX;
    int firstZ = path[1] + minZ + baseZ;
    while (controller.mud.packetHandler.getClientStream().hasFinishedPackets()) {
      controller.sleep(1);
    }
    controller.mud.packetHandler.getClientStream().newPacket(OP_WALK);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(firstX);
    controller.mud.packetHandler.getClientStream().bufferBits.putShort(firstZ);
    for (int i = 1; i < path.length / 2 && i <= 25; i++) {
      controller
          .mud
          .packetHandler
          .getClientStream()
          .bufferBits
          .putByte(path[2 * i] + minX + baseX - firstX);
      controller
          .mud
          .packetHandler
          .getClientStream()
          .bufferBits
          .putByte(path[2 * i + 1] + minZ + baseZ - firstZ);
    }
    controller.mud.packetHandler.getClientStream().finishPacket();
    return true;
  }

  /*
   * The server index of the block NPC not fighting on the tile, the lowest id then index first; -1
   * for none.
   */
  private int blockerOn(int x, int z) {
    ORSCharacter best = null;
    for (ORSCharacter npc : controller.getNpcs()) {
      if (npc == null || !contains(block, npc.npcId)) continue;
      if (serverX(npc) != x || serverZ(npc) != z || controller.isNpcInCombat(npc.serverIndex)) {
        continue;
      }
      if (best == null
          || npc.npcId < best.npcId
          || npc.npcId == best.npcId && npc.serverIndex < best.serverIndex) {
        best = npc;
      }
    }
    return best == null ? -1 : best.serverIndex;
  }

  /*
   * The stock client's breadth-first search, over flags[x][z]: the tiles where the path turns, from
   * the first to the first tile it reaches in the area (destX1, destZ1) to (destX2, destZ2), or,
   * beside, next to it with no wall between, as x, z pairs; null when it cannot be reached. A start
   * already there gives just the start.
   */
  private static int[] findPath(
      int[][] flags,
      int startX,
      int startZ,
      int destX1,
      int destZ1,
      int destX2,
      int destZ2,
      boolean beside) {
    int w = flags.length;
    int h = flags[0].length;
    int[][] source = new int[w][h];
    int[] qx = new int[w * h];
    int[] qz = new int[w * h];
    int read = 0;
    int write = 0;
    source[startX][startZ] = 99;
    qx[write] = startX;
    qz[write++] = startZ;
    int destX = -1;
    int destZ = -1;
    while (read != write) {
      int x = qx[read];
      int z = qz[read++];
      if (x >= destX1 && x <= destX2 && z >= destZ1 && z <= destZ2
          || beside
              && (x > 0
                      && x - 1 >= destX1
                      && x - 1 <= destX2
                      && z >= destZ1
                      && z <= destZ2
                      && (flags[x - 1][z] & WALL_WEST) == 0
                  || x < w - 1
                      && x + 1 >= destX1
                      && x + 1 <= destX2
                      && z >= destZ1
                      && z <= destZ2
                      && (flags[x + 1][z] & WALL_EAST) == 0
                  || z > 0
                      && x >= destX1
                      && x <= destX2
                      && z - 1 >= destZ1
                      && z - 1 <= destZ2
                      && (flags[x][z - 1] & WALL_SOUTH) == 0
                  || z < h - 1
                      && x >= destX1
                      && x <= destX2
                      && z + 1 >= destZ1
                      && z + 1 <= destZ2
                      && (flags[x][z + 1] & WALL_NORTH) == 0)) {
        destX = x;
        destZ = z;
        break;
      }
      if (x > 0 && source[x - 1][z] == 0 && (flags[x - 1][z] & (FULL_BLOCK | WALL_WEST)) == 0) {
        qx[write] = x - 1;
        qz[write++] = z;
        source[x - 1][z] = SOURCE_WEST;
      }
      if (x < w - 1 && source[x + 1][z] == 0 && (flags[x + 1][z] & (FULL_BLOCK | WALL_EAST)) == 0) {
        qx[write] = x + 1;
        qz[write++] = z;
        source[x + 1][z] = SOURCE_EAST;
      }
      if (z > 0 && source[x][z - 1] == 0 && (flags[x][z - 1] & (FULL_BLOCK | WALL_SOUTH)) == 0) {
        qx[write] = x;
        qz[write++] = z - 1;
        source[x][z - 1] = SOURCE_SOUTH;
      }
      if (z < h - 1
          && source[x][z + 1] == 0
          && (flags[x][z + 1] & (FULL_BLOCK | WALL_NORTH)) == 0) {
        qx[write] = x;
        qz[write++] = z + 1;
        source[x][z + 1] = SOURCE_NORTH;
      }
      if (x > 0
          && z > 0
          && (flags[x][z - 1] & (FULL_BLOCK | WALL_SOUTH)) == 0
          && (flags[x - 1][z] & (FULL_BLOCK | WALL_WEST)) == 0
          && (flags[x - 1][z - 1] & (FULL_BLOCK | WALL_SOUTH | WALL_WEST)) == 0
          && source[x - 1][z - 1] == 0) {
        qx[write] = x - 1;
        qz[write++] = z - 1;
        source[x - 1][z - 1] = SOURCE_SOUTH | SOURCE_WEST;
      }
      if (x < w - 1
          && z > 0
          && (flags[x][z - 1] & (FULL_BLOCK | WALL_SOUTH)) == 0
          && (flags[x + 1][z] & (FULL_BLOCK | WALL_EAST)) == 0
          && (flags[x + 1][z - 1] & (FULL_BLOCK | WALL_SOUTH | WALL_EAST)) == 0
          && source[x + 1][z - 1] == 0) {
        qx[write] = x + 1;
        qz[write++] = z - 1;
        source[x + 1][z - 1] = SOURCE_SOUTH | SOURCE_EAST;
      }
      if (x > 0
          && z < h - 1
          && (flags[x][z + 1] & (FULL_BLOCK | WALL_NORTH)) == 0
          && (flags[x - 1][z] & (FULL_BLOCK | WALL_WEST)) == 0
          && (flags[x - 1][z + 1] & (FULL_BLOCK | WALL_NORTH | WALL_WEST)) == 0
          && source[x - 1][z + 1] == 0) {
        qx[write] = x - 1;
        qz[write++] = z + 1;
        source[x - 1][z + 1] = SOURCE_NORTH | SOURCE_WEST;
      }
      if (x < w - 1
          && z < h - 1
          && (flags[x][z + 1] & (FULL_BLOCK | WALL_NORTH)) == 0
          && (flags[x + 1][z] & (FULL_BLOCK | WALL_EAST)) == 0
          && (flags[x + 1][z + 1] & (FULL_BLOCK | WALL_NORTH | WALL_EAST)) == 0
          && source[x + 1][z + 1] == 0) {
        qx[write] = x + 1;
        qz[write++] = z + 1;
        source[x + 1][z + 1] = SOURCE_NORTH | SOURCE_EAST;
      }
    }
    if (destX == -1) return null;
    // Back from the tile, keeping it and each tile where the direction changes.
    List<int[]> turns = new ArrayList<>();
    int x = destX;
    int z = destZ;
    turns.add(new int[] {x, z});
    int prev = source[x][z];
    int src = prev;
    while (x != startX || z != startZ) {
      if (src != prev) {
        prev = src;
        turns.add(new int[] {x, z});
      }
      if ((src & SOURCE_SOUTH) != 0) {
        z++;
      } else if ((src & SOURCE_NORTH) != 0) {
        z--;
      }
      if ((src & SOURCE_WEST) != 0) {
        x++;
      } else if ((src & SOURCE_EAST) != 0) {
        x--;
      }
      src = source[x][z];
    }
    int[] path = new int[turns.size() * 2];
    for (int i = 0; i < turns.size(); i++) {
      int[] t = turns.get(turns.size() - 1 - i);
      path[2 * i] = t[0];
      path[2 * i + 1] = t[1];
    }
    return path;
  }

  private int tiles(int x, int z) {
    return Math.max(Math.abs(x - px), Math.abs(z - pz));
  }

  private int floorTiles(int x, int z) {
    return z / FLOOR == pz / FLOOR ? tiles(x, z) : Integer.MAX_VALUE;
  }

  private static boolean contains(int[] array, int value) {
    for (int v : array) {
      if (v == value) return true;
    }
    return false;
  }

  @Override
  public void serverMessageInterrupt(String message) {
    if (message.contains("You have been standing here for")) {
      standing = true;
    }
  }

  @Override
  public void paintInterrupt() {
    if (controller == null || startMs < 0L) return;
    long now = tick;
    int x = 7;
    int y = 25;
    controller.drawString("@yel@" + getClass().getSimpleName(), x, y, 0xFFFFFF, 1);
    y += 14;
    controller.drawString(
        "@whi@" + controller.msToString(System.currentTimeMillis() - startMs), x, y, 0xFFFFFF, 1);
    y += 14;
    Target target = heading;
    String line = status;
    if (target != null && preAttackTick >= now - 1) line = "Pre-attacking #" + target.index;
    controller.drawString("@whi@" + line, x, y, 0xFFFFFF, 1);
    Map<Integer, ORSCharacter> seen = visible;
    for (Target t : targets) {
      y += 14;
      String when;
      if (seen.containsKey(t.index)) {
        when = "@gre@up";
      } else if (t.due == NOT_DUE) {
        when = "@lre@?";
      } else {
        when = "@yel@" + (Math.max(0L, t.due - now) * tickMs + 999) / 1000 + "s";
      }
      controller.drawString(
          "@whi@#" + t.index + " " + when + (t == target ? " @cya@<" : ""), x, y, 0xFFFFFF, 1);
    }
  }

  /* A saved recipe: its file, listed by its title. */
  private static final class Saved {
    final Path file;
    final String title;
    final long modified;

    Saved(Path file, String title, long modified) {
      this.file = file;
      this.title = title;
      this.modified = modified;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  private volatile boolean chosen;

  /*
   * Parses the saved recipe the arguments name, or else the one started from the window. False when
   * none was: the script stops.
   */
  private boolean chooseRecipe(String[] parameters) {
    List<Saved> saved = savedRecipes();
    String args = parameters == null ? "" : String.join(" ", parameters).trim();
    if (!key(args).isEmpty()) {
      for (Saved s : saved) {
        if (!key(s.title).equals(key(args)) && !key(baseName(s.file)).equals(key(args))) continue;
        try {
          parse(lines(read(s.file)));
          Files.setLastModifiedTime(s.file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IllegalArgumentException e) {
          controller.log(e.getMessage(), "red");
          controller.setAutoLogin(false);
          return false;
        } catch (IOException e) {
          controller.log("Error: " + e, "red");
          controller.setAutoLogin(false);
          return false;
        }
        return true;
      }
      controller.log("No saved recipe is called " + args, "red");
    }
    JFrame frame = showWindow(saved);
    while (!chosen && controller.isRunning() && frame.isDisplayable()) controller.sleep(250);
    if (frame.isDisplayable()) frame.dispose();
    return chosen;
  }

  private JFrame showWindow(List<Saved> saved) {
    JFrame frame = new JFrame(getClass().getSimpleName() + ": " + controller.getPlayerName());
    JComboBox<Saved> recipes = new JComboBox<>(saved.toArray(new Saved[0]));
    JTextArea text = new JTextArea(24, 80);
    text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    JLabel message = new JLabel(saved.isEmpty() ? "Paste a recipe from the map viewer." : " ");
    message.setForeground(new Color(0xD04040));
    JButton paste = new JButton("Paste");
    JButton delete = new JButton("Delete");
    JButton start = new JButton("Start");
    if (!saved.isEmpty()) show(text, saved.get(0));

    recipes.addActionListener(
        e -> {
          Saved s = (Saved) recipes.getSelectedItem();
          if (s != null) show(text, s);
          message.setText(" ");
        });
    paste.addActionListener(
        e -> {
          try {
            text.setText(
                (String)
                    Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .getData(DataFlavor.stringFlavor));
            text.setCaretPosition(0);
            message.setText(" ");
          } catch (Exception ex) {
            message.setText("The clipboard holds no text.");
          }
        });
    delete.addActionListener(
        e -> {
          Saved s = (Saved) recipes.getSelectedItem();
          if (s == null) return;
          try {
            Files.deleteIfExists(s.file);
          } catch (IOException ex) {
            message.setText("Can't delete " + s.file + ": " + ex.getMessage());
            return;
          }
          recipes.removeItem(s);
          if (recipes.getItemCount() == 0) text.setText("");
          message.setText("Deleted " + s.title);
        });
    start.addActionListener(
        e -> {
          String recipe = text.getText();
          String[] lines = lines(recipe);
          try {
            parse(lines);
          } catch (IllegalArgumentException ex) {
            message.setText(ex.getMessage());
            return;
          }
          try {
            save(recipe, lines);
          } catch (IOException ex) {
            controller.log("Can't save the recipe: " + ex.getMessage(), "red");
          }
          chosen = true;
          frame.dispose();
        });

    JPanel top = new JPanel(new BorderLayout(6, 0));
    top.add(new JLabel("Saved recipes"), BorderLayout.WEST);
    top.add(recipes, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(paste);
    buttons.add(delete);
    buttons.add(start);
    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(message, BorderLayout.CENTER);
    bottom.add(buttons, BorderLayout.EAST);
    JPanel panel = new JPanel(new BorderLayout(0, 6));
    panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    panel.add(top, BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(text);
    scroll.setPreferredSize(new Dimension(640, 420));
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(bottom, BorderLayout.SOUTH);

    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    frame.add(panel);
    frame.pack();
    frame.setLocationRelativeTo(Main.rscFrame);
    frame.setVisible(true);
    frame.requestFocus();
    return frame;
  }

  private static void show(JTextArea text, Saved s) {
    try {
      text.setText(read(s.file));
    } catch (IOException e) {
      text.setText("# Can't read " + s.file + ": " + e.getMessage());
    }
    text.setCaretPosition(0);
  }

  /* The saved recipes, newest first. */
  private static List<Saved> savedRecipes() {
    List<Saved> saved = new ArrayList<>();
    if (!Files.isDirectory(RECIPES)) return saved;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(RECIPES, "*.txt")) {
      for (Path file : files) {
        String title = titleOf(lines(read(file)));
        long modified = Files.getLastModifiedTime(file).toMillis();
        saved.add(new Saved(file, title == null ? baseName(file) : title, modified));
      }
    } catch (IOException e) {
      Main.logError("Can't list " + RECIPES, e);
    }
    saved.sort((a, b) -> Long.compare(b.modified, a.modified));
    return saved;
  }

  /* Saves it under its title, over a saved recipe of the same title. */
  private static void save(String recipe, String[] lines) throws IOException {
    String title = titleOf(lines);
    String name = (title == null ? "" : title).replaceAll("[^A-Za-z0-9 @,()._-]", "_").trim();
    name = name.replaceAll("[. ]+$", "");
    if (name.length() > 80) name = name.substring(0, 80).trim();
    if (name.isEmpty()) name = "Recipe";
    Files.createDirectories(RECIPES);
    Files.write(RECIPES.resolve(name + ".txt"), recipe.getBytes(StandardCharsets.UTF_8));
  }

  /* The text of its first comment line; null when it has none. */
  private static String titleOf(String[] lines) {
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("#")) {
        String title = trimmed.substring(1).trim();
        return title.isEmpty() ? null : title;
      }
    }
    return null;
  }

  private static String read(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  private static String[] lines(String text) {
    return text.split("\\r?\\n");
  }

  private static String baseName(Path file) {
    String name = file.getFileName().toString();
    return name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
  }

  /* Letters and digits, lower case: IdleRSC lowercases account-file arguments, strips spaces. */
  private static String key(String name) {
    return name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
  }

  private void parse(String[] recipe) {
    targets.clear();
    byIndex.clear();
    carries.clear();
    tickMs = TICK_MS;
    style = -1;
    eatAt = -1;
    sleepAt = -1;
    foods = new int[0];
    loot = new int[0];
    bury = new int[0];
    block = new int[0];
    bankRoute = null;
    campRoute = null;
    Map<String, Integer> seen = new HashMap<>();
    List<Step> steps = null;
    List<Step> all = new ArrayList<>();
    String routeName = null;
    int routeLine = 0;
    int last = 0;
    for (int i = 0; i < recipe.length; i++) {
      int line = i + 1;
      String text = recipe[i];
      if (!text.trim().isEmpty()) last = line;
      int hash = text.indexOf('#');
      if (hash >= 0) text = text.substring(0, hash);
      text = text.trim();
      if (text.isEmpty()) continue;
      String[] w = text.split("\\s+");
      if (steps != null) {
        if (w[0].equals("end")) {
          numbers(w, 0, line);
          if (steps.isEmpty()) throw error(line, "route " + routeName + " has no steps");
          Step[] array = steps.toArray(new Step[0]);
          if (routeName.equals("bank")) {
            bankRoute = array;
          } else {
            campRoute = array;
          }
          all.addAll(steps);
          steps = null;
        } else {
          steps.add(step(w, line, text));
        }
        continue;
      }
      switch (w[0]) {
        case "world":
          once(seen, "world", line);
          if (w.length != 3) throw error(line, "world takes 1 numbers");
          tickMs = num(w[2], line);
          if (tickMs < 1) throw error(line, "world is 1 to 2147483647");
          break;
        case "target":
          numbers(w, 9, line);
          Target t = new Target();
          t.index = num(w[1], line);
          t.npcId = num(w[2], line);
          t.x = num(w[3], line);
          t.z = num(w[4], line);
          t.respawn = num(w[5], line);
          t.minX = num(w[6], line);
          t.minZ = num(w[7], line);
          t.maxX = num(w[8], line);
          t.maxZ = num(w[9], line);
          once(seen, "target " + t.index, line);
          if (t.minX > t.maxX || t.minZ > t.maxZ) {
            throw error(line, "the box's min is above its max");
          }
          byIndex.put(t.index, t);
          targets.add(t);
          break;
        case "camp":
          once(seen, "camp", line);
          numbers(w, 6, line);
          campX = num(w[1], line);
          campZ = num(w[2], line);
          campMinX = num(w[3], line);
          campMinZ = num(w[4], line);
          campMaxX = num(w[5], line);
          campMaxZ = num(w[6], line);
          if (campMinX > campMaxX || campMinZ > campMaxZ) {
            throw error(line, "the box's min is above its max");
          }
          break;
        case "bank":
          once(seen, "bank", line);
          numbers(w, 2, line);
          bankX = num(w[1], line);
          bankZ = num(w[2], line);
          break;
        case "style":
          once(seen, "style", line);
          numbers(w, 1, line);
          style = num(w[1], line);
          if (style > 3) throw error(line, "style is 0 to 3");
          break;
        case "eat":
          once(seen, "eat", line);
          numbers(w, 1, line);
          eatAt = num(w[1], line);
          break;
        case "sleep":
          once(seen, "sleep", line);
          numbers(w, 1, line);
          sleepAt = num(w[1], line);
          if (sleepAt < 1 || sleepAt > 100) throw error(line, "sleep is 1 to 100");
          break;
        case "food":
          once(seen, "food", line);
          foods = ids(w, line);
          break;
        case "item":
          if (w.length != 4 && !(w.length == 5 && w[4].equals("wear"))) {
            throw error(line, "item takes 3 numbers");
          }
          Carry c = new Carry();
          c.id = num(w[1], line);
          c.min = num(w[2], line);
          c.target = num(w[3], line);
          c.wear = w.length == 5;
          once(seen, "item " + c.id, line);
          if (c.min > c.target) throw error(line, "min is above target");
          carries.add(c);
          break;
        case "loot":
          once(seen, "loot", line);
          loot = ids(w, line);
          break;
        case "bury":
          once(seen, "bury", line);
          bury = ids(w, line);
          break;
        case "block":
          once(seen, "block", line);
          block = ids(w, line);
          break;
        case "route":
          if (w.length != 2 || (!w[1].equals("bank") && !w[1].equals("camp"))) {
            throw error(line, "unknown instruction route");
          }
          once(seen, "route " + w[1], line);
          steps = new ArrayList<>();
          routeName = w[1];
          routeLine = line;
          break;
        default:
          throw error(line, "unknown instruction " + w[0]);
      }
    }
    if (steps != null) throw error(routeLine, "route " + routeName + " has no end");
    if (targets.isEmpty()) throw error(last, "no target line");
    if (!seen.containsKey("camp")) throw error(last, "no camp line");
    if (!seen.containsKey("bank")) throw error(last, "no bank line");
    if (bankRoute == null) throw error(last, "no route bank line");
    if (campRoute == null) throw error(last, "no route camp line");
    for (Step s : all) {
      if (s.item == 0) continue;
      Carry c = carry(s.item);
      if (c == null || c.min < 1) {
        throw error(
            s.line, KINDS[s.kind] + " needs an item line for " + s.item + " with min 1 or more");
      }
    }
    Carry bag = carry(SLEEPING_BAG);
    if (sleepAt != -1 && (bag == null || bag.min < 1)) {
      throw error(
          seen.get("sleep"),
          "sleep needs an item line for " + SLEEPING_BAG + " with min 1 or more");
    }
  }

  /* The key names what may appear once; the message names the line's first word. */
  private static void once(Map<String, Integer> seen, String key, int line) {
    Integer before = seen.put(key, line);
    if (before != null) {
      throw error(line, key.split(" ")[0] + " is already on line " + before);
    }
  }

  private static Step step(String[] w, int line, String text) {
    Step s = new Step();
    s.kind = -1;
    for (int k = 0; k < KINDS.length; k++) {
      if (KINDS[k].equals(w[0])) s.kind = k;
    }
    if (s.kind == -1) throw error(line, "unknown instruction " + w[0]);
    numbers(w, NUMBERS[s.kind], line);
    s.line = line;
    s.text = text;
    s.x = num(w[1], line);
    s.z = num(w[2], line);
    switch (s.kind) {
      case DOOR:
        s.dir = num(w[3], line);
        s.closedId = num(w[4], line);
        break;
      case GATE:
        s.closedId = num(w[3], line);
        break;
      case OBJ:
        s.cmd = num(w[3], line);
        s.landX = num(w[4], line);
        s.landZ = num(w[5], line);
        break;
      case WALL:
        s.dir = num(w[3], line);
        s.cmd = num(w[4], line);
        s.landX = num(w[5], line);
        s.landZ = num(w[6], line);
        break;
      case CUT:
        s.dir = num(w[3], line);
        s.closedId = num(w[4], line);
        s.item = num(w[5], line);
        break;
      case USEWALL:
        s.dir = num(w[3], line);
        s.item = num(w[4], line);
        s.landX = num(w[5], line);
        s.landZ = num(w[6], line);
        break;
      case USEOBJ:
        s.item = num(w[3], line);
        s.landX = num(w[4], line);
        s.landZ = num(w[5], line);
        break;
      default:
        break;
    }
    if ((s.kind == DOOR || s.kind == WALL || s.kind == CUT || s.kind == USEWALL) && s.dir > 3) {
      throw error(line, "dir is 0 to 3");
    }
    if ((s.kind == OBJ || s.kind == WALL) && (s.cmd < 1 || s.cmd > 2)) {
      throw error(line, "cmd is 1 to 2");
    }
    if ((s.kind == USEWALL || s.kind == USEOBJ) && s.item < 1) {
      throw error(line, "itemId is 1 to 2147483647");
    }
    return s;
  }

  private static void numbers(String[] w, int count, int line) {
    if (w.length != count + 1) throw error(line, w[0] + " takes " + count + " numbers");
  }

  private static int[] ids(String[] w, int line) {
    int[] values = new int[w.length - 1];
    for (int i = 1; i < w.length; i++) values[i - 1] = num(w[i], line);
    return values;
  }

  /* A whole number from 0 to 2147483647, written in digits only; leading zeros are fine. */
  private static int num(String word, int line) {
    String digits = word.replaceFirst("^0+(?=.)", "");
    if (!word.matches("[0-9]+")
        || digits.length() > 10
        || Long.parseLong(digits) > Integer.MAX_VALUE) {
      throw error(line, word + " is not a number");
    }
    return Integer.parseInt(digits);
  }

  private static IllegalArgumentException error(int line, String message) {
    return new IllegalArgumentException("line " + line + ": " + message);
  }
}
