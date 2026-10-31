import com.aposbot.Constants;
import com.aposbot.StandardCloseHandler;
import com.aposbot._default.IScriptListener;
import com.aposbot.handler.CameraHandler;
import com.aposbot.utility.LocalRouteCalc;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.lang.System.currentTimeMillis;

/*
 * Camps NPC spawns and attacks the one it waits for every tick, so the attack lands the tick it
 * respawns. Everything it needs comes from a camp recipe pasted into its window, which it keeps in
 * recipes/Bob_MapCamper/; see RECIPE.md for the format and what it does. A saved recipe's title in
 * the script selector's parameter box starts it without the window.
 */
public final class Bob_MapCamper extends Script {

	private static final Path RECIPE_DIR = Paths.get("recipes", "Bob_MapCamper");

	private Frame frame;
	private Choice ch_recipes;
	private TextArea ta_recipe;
	private Label lb_status;
	/* the saved recipes, in the dropdown's order: newest first */
	private final List<Path> saved = new ArrayList<Path>();

	private final DecimalFormat iformat = new DecimalFormat("#,##0");
	private final int[] start_xp = new int[SKILL.length];
	private long start_time = -1L;

	private static final int SKILL_HITS = 3;
	private static final int SLEEPING_BAG = 1263;

	/*
	 * The server sends us the NPCs whose straight-line distance, rounded down, is at most 15: under 16
	 * tiles. From within this, squared, a diagonal step cannot take an NPC out of view, so one that
	 * leaves view from this close died
	 * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369
	 * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L409
	 */
	private static final int VIEW_SQ = 212;
	/* spawn timers are drawn for spawns this close */
	private static final int LABEL_TILES = 20;
	/* a route is resumed from its nearest step no further away than this */
	private static final int RESUME_TILES = 30;
	/* a walk step is done this close to its tile */
	private static final int STEP_TILES = 2;
	private static final int[] BANKER_IDS = {95, 224, 268, 540, 617, 792};
	/* a client position unit per tile */
	private static final int TILE_UNITS = 128;
	/* no server update for this long is a reconnect: APOS gives a script no login hook */
	private static final long RELOG_MS = 5000L;
	/* a deposit or withdrawal still to do this long after it was first sent stops the script */
	private static final int BANK_TICKS = 3;
	/* a route step taking this long is logged, and again every time as long */
	private static final int STALL_TICKS = 20;
	/* further away than this, a target is walked to before it is attacked */
	private static final int APPROACH_TILES = 5;
	/* loot further away than this, but on the player's own tile, is left */
	private static final int LOOT_TILES = 4;
	/* a hunted target due within this many ticks, with the player at its spawn, is kept and not left to loot */
	private static final int DUE_SOON_TICKS = 5;
	/* added to a hunt's ticks when the target may not be there, or its walk outlasts its wait by over LATE_TICKS */
	private static final int UNSURE_TICKS = 6;
	private static final int LATE_TICKS = 5;
	/* the last hunt's target is kept over one fewer than this many ticks sooner */
	private static final int KEEP_TICKS = 2;
	/* a spawn the client's walking could not reach is skipped for this many ticks */
	private static final int UNREACHABLE_TICKS = 50;
	/* close enough to the bank tile to talk to a banker */
	private static final int BANK_TILES = 3;
	private static final int FLOOR = 944;
	/* a walk round the block NPCs searches the box of the player and its tile grown by this */
	private static final int SEARCH_TILES = 16;
	private static final int OP_WALK = 187;
	/* the client's collision flags */
	private static final int WALL_NORTH = 1, WALL_EAST = 2, WALL_SOUTH = 4, WALL_WEST = 8;
	private static final int FULL_BLOCK = 16 | 32 | 64;
	private static final int SOURCE_SOUTH = 1, SOURCE_WEST = 2, SOURCE_NORTH = 4, SOURCE_EAST = 8;

	private static final int DONE = 0, WAIT = 1, BACK = 2;

	/* the client's trig table: 1024 steps to a turn, sin then cos, scaled by 2^15 */
	private static final int[] SIN = new int[2048];

	static {
		for (int i = 0; i < 1024; i++) {
			SIN[i] = (int) (Math.sin(i * 0.00613592315D) * 32768.0D);
			SIN[i + 1024] = (int) (Math.cos(i * 0.00613592315D) * 32768.0D);
		}
	}

	private final Extension ex;
	private final LocalRouteCalc route_calc = new LocalRouteCalc();
	private int paint_max_y;

	/* from the recipe */
	private boolean parsed;
	private String world;
	private long tick_ms = 640L;
	private Target[] targets = new Target[0];
	private int camp_x, camp_y, camp_min_x, camp_min_y, camp_max_x, camp_max_y;
	private int bank_x, bank_y;
	private int style = -1;
	private int eat_at;
	/* the fatigue in percent to sleep at; -1 for never */
	private int sleep_at = -1;
	private int[] food_ids = new int[0];
	private Item[] items = new Item[0];
	private int[] loot_ids = new int[0];
	private int[] banked_count = new int[0];
	private int[] bury_ids = new int[0];
	private int[] block_ids = new int[0];
	private int[] pickup_ids = new int[0];
	private Step[] route_bank = new Step[0];
	private Step[] route_camp = new Step[0];

	/* server updates since the start */
	private long tick;
	/* a server update's position packet was handled since the last decision, when, and the player's tile then */
	private boolean update_seen;
	private long update_ms;
	private int update_x, update_y;
	private boolean started;
	private boolean stopped;
	private boolean standing_warned;
	private boolean out_of_food_logged;

	/* the tick deposits, and withdrawals, still to do were first sent; -1 when none are */
	private long deposits_tick = -1L, withdrawals_tick = -1L;

	/* the route being followed and its step; route bank stays, at its end, until the bank is near */
	private Step[] route;
	private String route_name;
	private int step;
	private int step_max;
	private long stall_tick, stall_log_tick;
	/* route camp ran to its end, or we started in the camp: camp until we leave it */
	private boolean arrived;

	/* the target waited for this tick, attacked by server index until it is in view */
	private Target camp;
	private boolean attacked_blocker;
	private long pre_attack_tick = -1L;
	/* what the last hunt chose, and the tile it walked to or waited on */
	private Target hunted;
	private int[] hunt_tile;
	/* the server index of the last block NPC attacked as in the way: a fight with it is not broken off */
	private int blocker_index = -1;

	/* APOS's whole-map walker, for a start too far from the route to resume it */
	private PathWalker walker;
	private Step[] far_route;
	private int[] far_tile;
	private final List<Long> far_tried = new ArrayList<Long>();

	public Bob_MapCamper(Extension ex) {
		super(ex);
		this.ex = ex;
	}

	public static void main(String[] argv) {
		new Bob_MapCamper(null).init(null);
	}

	@Override
	public void init(String params) {
		parsed = false;
		if (params != null && !params.trim().isEmpty()) {
			Path path = find_saved(params);
			if (path == null) {
				System.out.println("No saved recipe is called " + params.trim());
			} else {
				String error;
				try {
					error = start(read(path));
				} catch (IOException e) {
					error = "Could not read " + path + ": " + e;
				}
				if (error == null) return;
				System.out.println(error);
			}
		}
		show_frame();
	}

	/* parses, saves and runs the recipe; the parse error, or null when it runs */
	private String start(String text) {
		Recipe r;
		try {
			r = Recipe.parse(text.split("\\r?\\n", -1));
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		try {
			save(text);
		} catch (IOException e) {
			return "Could not save the recipe: " + e;
		}
		apply(r);
		System.out.printf("Camping %d spawns around %d,%d; banking at %d,%d%n", targets.length, camp_x, camp_y,
			bank_x, bank_y);
		/* after the script selector has made this script the current one */
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				IScriptListener listener = ScriptListener.getInstance();
				if (listener.hasScript() && listener.getScriptName().equals(Bob_MapCamper.this.toString()) &&
					!listener.isScriptRunning()) {
					listener.setScriptRunning(true);
				}
			}
		});
		return null;
	}

	private void apply(Recipe r) {
		start_time = -1L;
		camp = null;
		route = null;
		world = r.world;
		tick_ms = r.tick_ms;
		targets = r.targets.toArray(new Target[0]);
		camp_x = r.camp_x;
		camp_y = r.camp_y;
		camp_min_x = r.camp_min_x;
		camp_min_y = r.camp_min_y;
		camp_max_x = r.camp_max_x;
		camp_max_y = r.camp_max_y;
		bank_x = r.bank_x;
		bank_y = r.bank_y;
		style = r.style;
		eat_at = r.eat_at;
		sleep_at = r.sleep_at;
		food_ids = r.food_ids;
		items = r.items.toArray(new Item[0]);
		loot_ids = r.loot_ids;
		banked_count = new int[loot_ids.length];
		bury_ids = r.bury_ids;
		block_ids = r.block_ids;
		pickup_ids = new int[loot_ids.length + bury_ids.length];
		System.arraycopy(loot_ids, 0, pickup_ids, 0, loot_ids.length);
		System.arraycopy(bury_ids, 0, pickup_ids, loot_ids.length, bury_ids.length);
		route_bank = r.route_bank;
		route_camp = r.route_camp;
		parsed = true;
	}

	private void show_frame() {
		if (frame == null) {
			ch_recipes = new Choice();
			ch_recipes.addItemListener(e -> show_selected());
			ta_recipe = new TextArea("", 24, 80, TextArea.SCROLLBARS_BOTH);
			ta_recipe.setFont(new Font("Monospaced", Font.PLAIN, 12));
			lb_status = new Label(" ");

			Panel top = new Panel(new BorderLayout(6, 0));
			top.add(new Label("Saved recipes:"), BorderLayout.WEST);
			top.add(ch_recipes, BorderLayout.CENTER);

			Button paste = new Button("Paste");
			paste.addActionListener(e -> paste());
			Button start = new Button("Start");
			start.addActionListener(e -> {
				String error = start(ta_recipe.getText());
				if (error != null) {
					status(error, true);
				} else {
					frame.setVisible(false);
				}
			});
			Button delete = new Button("Delete");
			delete.addActionListener(e -> delete());
			Panel buttons = new Panel();
			buttons.add(paste);
			buttons.add(start);
			buttons.add(delete);

			Panel bottom = new Panel(new GridLayout(0, 1));
			bottom.add(lb_status);
			bottom.add(buttons);

			frame = new Frame(getClass().getSimpleName());
			frame.setIconImages(Constants.ICONS);
			frame.addWindowListener(new StandardCloseHandler(frame, StandardCloseHandler.HIDE));
			frame.add(top, BorderLayout.NORTH);
			frame.add(ta_recipe, BorderLayout.CENTER);
			frame.add(bottom, BorderLayout.SOUTH);
			frame.pack();
		}
		refresh();
		show_selected();
		status(saved.isEmpty() ? "Paste a recipe from the map viewer, then Start." : " ", false);
		frame.setLocationRelativeTo(null);
		frame.toFront();
		frame.requestFocus();
		frame.setVisible(true);
	}

	private void status(String text, boolean error) {
		lb_status.setForeground(error ? Color.RED : Color.BLACK);
		lb_status.setText(text);
	}

	private void paste() {
		try {
			String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			ta_recipe.setText(text);
			status("Pasted \"" + Recipe.title(text) + "\": press Start to save and run it.", false);
		} catch (Exception e) {
			status("The clipboard holds no text.", true);
		}
	}

	private void delete() {
		int i = ch_recipes.getSelectedIndex();
		if (i < 0 || i >= saved.size()) return;
		try {
			Files.deleteIfExists(saved.get(i));
			status("Deleted \"" + ch_recipes.getSelectedItem() + "\".", false);
		} catch (IOException e) {
			status("Could not delete it: " + e, true);
		}
		refresh();
		show_selected();
	}

	private void show_selected() {
		int i = ch_recipes.getSelectedIndex();
		if (i < 0 || i >= saved.size()) {
			ta_recipe.setText("");
			return;
		}
		try {
			ta_recipe.setText(read(saved.get(i)));
		} catch (IOException e) {
			status("Could not read it: " + e, true);
		}
	}

	/* reloads the dropdown from the recipe folder, newest first */
	private void refresh() {
		saved.clear();
		saved.addAll(list_saved());
		ch_recipes.removeAll();
		for (Path path : saved) {
			ch_recipes.add(title_of(path));
		}
	}

	private static List<Path> list_saved() {
		final List<Path> paths = new ArrayList<Path>();
		try {
			Files.createDirectories(RECIPE_DIR);
			try (DirectoryStream<Path> dir = Files.newDirectoryStream(RECIPE_DIR, "*.txt")) {
				for (Path path : dir) {
					paths.add(path);
				}
			}
		} catch (IOException e) {
			System.out.println("Could not list " + RECIPE_DIR + ": " + e);
		}
		Collections.sort(paths, new Comparator<Path>() {
			@Override
			public int compare(Path a, Path b) {
				return Long.compare(modified(b), modified(a));
			}
		});
		return paths;
	}

	private static long modified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	/* the saved recipe whose title matches, ignoring case, spaces and punctuation */
	private static Path find_saved(String name) {
		String key = Recipe.key(name);
		for (Path path : list_saved()) {
			if (Recipe.key(title_of(path)).equals(key)) return path;
		}
		return null;
	}

	private static String title_of(Path path) {
		try {
			return Recipe.title(read(path));
		} catch (IOException e) {
			return path.getFileName().toString();
		}
	}

	private static String read(Path path) throws IOException {
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}

	/* writes the recipe under its title, replacing one of the same title; it then lists first */
	private static void save(String text) throws IOException {
		Files.createDirectories(RECIPE_DIR);
		Path path = RECIPE_DIR.resolve(Recipe.file_name(Recipe.title(text)));
		Files.write(path, text.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(path, FileTime.fromMillis(currentTimeMillis()));
	}

	@Override
	public int main() {
		if (!parsed) {
			System.out.println("Choose a recipe in the script's window and press Start");
			stopScript();
			return 0;
		}
		if (start_time == -1L) {
			ingame_init();
		}
		/* an update not decided on before a disconnect is no update of this login */
		if (!ex.isLoggedIn() || update_seen && currentTimeMillis() - update_ms > RELOG_MS) update_seen = false;
		if (stopped || !update_seen || !update_applied()) return 0;
		update_seen = false;
		decide(getX(), getY());
		return 0;
	}

	/* observes the update that put the player on (px, py), then does one tick */
	private void decide(int px, int py) {
		camp = null;
		attacked_blocker = false;
		try {
			observe(px, py);
			act();
			/*
			 * after any walk this tick: a walk cancels an attack sent before it, not one sent after
			 * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L94
			 */
			if (!stopped) pre_attack();
		} catch (RuntimeException e) {
			stop("Error: " + e);
		}
	}

	/*
	 * Whether the client has read every byte the server sent: main runs on the client's thread between
	 * its packet reads, one packet a frame, and a packet whose length is read but whose body has not
	 * arrived yet leaves the stream's pending length set.
	 */
	private boolean update_applied() {
		da stream = ex.Jh;
		if (stream == null) return false;
		try {
			return stream.A == 0 && stream.U.available() == 0;
		} catch (IOException e) {
			return false;
		}
	}

	private void ingame_init() {
		start_time = currentTimeMillis();
		for (int i = 0; i < start_xp.length; ++i) {
			start_xp[i] = getXpForLevel(i);
		}
		tick = 0L;
		update_seen = false;
		update_ms = 0L;
		started = false;
		stopped = false;
		standing_warned = false;
		out_of_food_logged = false;
		deposits_tick = withdrawals_tick = -1L;
		route = null;
		far_route = null;
		far_tried.clear();
		arrived = false;
		hunted = null;
		hunt_tile = null;
		blocker_index = -1;
		for (Target t : targets) {
			t.pop = 0L;
			t.unreachable_until = 0L;
			t.tile = null;
			t.waited = 0L;
			t.seen = null;
		}
	}

	/* One tick: the first of RECIPE.md's steps that applies. */
	private void act() {
		if (!started) {
			started = true;
			arrived = in_camp(getX(), getY()) && isReachable(camp_x, camp_y);
		}
		if (food_slot() != -1) out_of_food_logged = false;
		if (!in_camp_grown(getX(), getY())) arrived = false;
		if (!arrived) {
			hunted = null;
			hunt_tile = null;
		}

		if (fighting()) {
			if (getCurrentLevel(SKILL_HITS) <= eat_at) {
				walkTo(getX(), getY());
			} else if (breaks_off()) {
				int[] t = break_off_tile();
				walkTo(t[0], t[1]);
			}
			return;
		}
		if (isBanking()) {
			if (route == route_bank) route = null;
			banking();
			return;
		}
		deposits_tick = withdrawals_tick = -1L;
		if (isQuestMenu()) {
			answer(0);
			return;
		}
		if (style != -1 && getFightMode() != style) {
			setFightMode(style);
			return;
		}
		if (getCurrentLevel(SKILL_HITS) <= eat_at) {
			int food = food_slot();
			if (food != -1) {
				useItem(food);
				return;
			}
			if (!out_of_food_logged) {
				System.out.println("Out of food");
				out_of_food_logged = true;
			}
		}
		for (Item it : items) {
			if (!it.wear) continue;
			int slot = getInventoryIndex(it.id);
			if (slot == -1 || worn(it.id)) continue;
			wearItem(slot);
			return;
		}
		for (int id : bury_ids) {
			int slot = getInventoryIndex(id);
			if (slot == -1) continue;
			useItem(slot);
			return;
		}
		if (standing_warned) {
			standing_warned = false;
			if (step_aside()) return;
		}
		boolean bank_near = tiles(bank_x, bank_y) <= BANK_TILES;
		if (bank_near && route == route_bank) route = null;
		/* route bank runs to its end; needing the bank on route camp turns back at once */
		if (need_bank() || route == route_bank) {
			if (bank_near || route == route_bank && step >= route.length) {
				int banker = nearest_banker();
				if (banker != -1) {
					talkToNpc(banker);
				} else if (getX() != bank_x || getY() != bank_y) {
					walk(bank_x, bank_y);
				}
				return;
			}
			follow(route_bank, "bank");
			return;
		}
		if (!arrived) {
			follow(route_camp, "camp");
			if (!arrived) return;
		}
		if (loot()) return;
		hunt();
	}

	/* the client says so, or an NPC fighting stands on the player's tile: the client can say so a tick or two late */
	private boolean fighting() {
		return inCombat() || opponent() != -1;
	}

	/* the NPC fighting on the player's tile, the lowest server index of several; -1 for none */
	private int opponent() {
		int opponent = -1;
		int count = countNpcs();
		for (int i = 0; i < count; i++) {
			if (!isNpcInCombat(i) || npc_x(i) != getX() || npc_y(i) != getY()) continue;
			if (opponent == -1 || getNpcServerIndex(i) < getNpcServerIndex(opponent)) opponent = i;
		}
		return opponent;
	}

	/* fighting an NPC that is not a target, nor the block NPC last attacked as in the way */
	private boolean breaks_off() {
		int opponent = opponent();
		if (opponent == -1) return false;
		int index = getNpcServerIndex(opponent);
		int id = getNpcId(opponent);
		return target_of(index, id) == null && !(index == blocker_index && inArray(block_ids, id));
	}

	/* the current route step's tile, else, camping, the tile it hunts at; its own when there is none or it is not reached */
	private int[] break_off_tile() {
		int[] t = null;
		if (route != null && step < route.length) {
			t = new int[]{route[step].x, route[step].y};
		} else if (arrived) {
			t = hunt_tile;
		}
		if (t == null || !isReachable(t[0], t[1])) return new int[]{getX(), getY()};
		return t;
	}

	private void stop(String why) {
		System.out.println(why);
		stopped = true;
		setAutoLogin(false);
		stopScript();
	}

	private boolean worn(int id) {
		int count = getInventoryCount();
		for (int i = 0; i < count; i++) {
			if (getInventoryId(i) == id && isItemEquipped(i)) return true;
		}
		return false;
	}

	private int food_slot() {
		for (int id : food_ids) {
			int slot = getInventoryIndex(id);
			if (slot != -1) return slot;
		}
		return -1;
	}

	private Item item_of(int id) {
		for (Item it : items) {
			if (it.id == id) return it;
		}
		return null;
	}

	private boolean need_bank() {
		for (Item it : items) {
			if (getInventoryCount(it.id) < it.min) return true;
		}
		return getInventoryCount() == MAX_INV_SIZE && !depositable_ids().isEmpty();
	}

	/* the ids of what no item line names and is not worn */
	private List<Integer> depositable_ids() {
		List<Integer> ids = new ArrayList<Integer>();
		int count = getInventoryCount();
		for (int i = 0; i < count; i++) {
			int id = getInventoryId(i);
			if (!isItemEquipped(i) && item_of(id) == null && !ids.contains(id)) ids.add(id);
		}
		return ids;
	}

	/* how many of the item are carried but not worn */
	private int unworn_count(int id) {
		int n = 0;
		int count = getInventoryCount();
		for (int i = 0; i < count; i++) {
			if (getInventoryId(i) != id || isItemEquipped(i)) continue;
			n += isItemStackableId(id) ? getInventoryStack(i) : 1;
		}
		return n;
	}

	/* whether a at (ax, az) with id a_id comes before b: nearer, then lower x, z and id */
	private boolean before(int ax, int az, int a_id, int bx, int bz, int b_id) {
		int da = tiles(ax, az);
		int db = tiles(bx, bz);
		if (da != db) return da < db;
		if (ax != bx) return ax < bx;
		if (az != bz) return az < bz;
		return a_id < b_id;
	}

	private int nearest_banker() {
		int best = -1;
		int count = countNpcs();
		for (int i = 0; i < count; i++) {
			if (!inArray(BANKER_IDS, getNpcId(i))) continue;
			if (best == -1 || before(npc_x(i), npc_y(i), getNpcId(i), npc_x(best), npc_y(best), getNpcId(best))) {
				best = i;
			}
		}
		return best;
	}

	private void banking() {
		List<Integer> deposits = depositable_ids();
		if (!deposits.isEmpty()) {
			if (deposits_tick == -1L) {
				deposits_tick = tick;
			} else if (tick >= deposits_tick + BANK_TICKS) {
				stop("The bank did not take deposits");
				return;
			}
			for (int id : deposits) {
				int count = unworn_count(id);
				if (deposits_tick == tick) {
					for (int i = 0; i < loot_ids.length; i++) {
						if (loot_ids[i] == id) banked_count[i] += count;
					}
				}
				deposit(id, count);
			}
			return;
		}
		deposits_tick = -1L;
		int free = getEmptySlots();
		int[] take = new int[items.length];
		for (int i = 0; i < items.length; i++) {
			Item it = items[i];
			int have = getInventoryCount(it.id);
			if (have >= it.min) continue;
			int in_bank = bankCount(it.id);
			if (have + in_bank < it.min) {
				stop(String.format("Out of %s: %d carried, %d in the bank, min %d",
					getItemNameId(it.id), have, in_bank, it.min));
				return;
			}
			int slots = isItemStackableId(it.id) ? (have > 0 ? 0 : 1) : it.min - have;
			if (slots > free) {
				stop("No room for " + getItemNameId(it.id));
				return;
			}
			free -= slots;
			take[i] = it.min - have;
		}
		for (int i = 0; i < items.length; i++) {
			Item it = items[i];
			int have = getInventoryCount(it.id) + take[i];
			int n = Math.min(it.target - have, bankCount(it.id) - take[i]);
			if (n <= 0) continue;
			if (isItemStackableId(it.id)) {
				if (have == 0) {
					if (free == 0) continue;
					free--;
				}
			} else {
				n = Math.min(n, free);
				free -= n;
			}
			take[i] += n;
		}
		boolean any = false;
		for (int t : take) {
			if (t > 0) any = true;
		}
		if (!any) {
			withdrawals_tick = -1L;
			closeBank();
			return;
		}
		if (withdrawals_tick == -1L) {
			withdrawals_tick = tick;
		} else if (tick >= withdrawals_tick + BANK_TICKS) {
			stop("The bank did not take withdrawals");
			return;
		}
		for (int i = 0; i < items.length; i++) {
			if (take[i] > 0) withdraw(items[i].id, take[i]);
		}
	}

	/* Follows a route from its step nearest the player through to its last step. */
	private void follow(Step[] steps, String name) {
		if (route != steps || step < route.length && floor_tiles(route[step].x, route[step].y) > RESUME_TILES) {
			int k = resume(steps);
			if (k == -1) {
				far_walk(steps, name);
				return;
			}
			if (far_tile != null) {
				walker_call(new Runnable() {
					@Override
					public void run() {
						walker.setPath(null);
					}
				});
				far_tile = null;
			}
			far_route = null;
			far_tried.clear();
			route = steps;
			route_name = name;
			step = step_max = k;
			stall_tick = tick;
			stall_log_tick = tick + STALL_TICKS + 1;
			System.out.printf("Route %s: from step %d of %d%n", name, k + 1, steps.length);
		}
		while (step < route.length) {
			int r = run_step(route[step]);
			if (stopped) return;
			if (r == DONE) {
				step++;
				if (step > step_max) {
					step_max = step;
					stall_tick = tick;
					stall_log_tick = tick + STALL_TICKS + 1;
				}
				continue;
			}
			/* someone shut the door behind us, or the web grew back: it is done again this tick */
			if (r == BACK) {
				step = door_before(route, step);
				continue;
			}
			if (tick >= stall_log_tick) {
				System.out.printf("Route %s step %d (%s) for %d ticks%n", route_name, step + 1, route[step].text,
					tick - stall_tick);
				stall_log_tick = tick + STALL_TICKS;
			}
			return;
		}
		System.out.printf("Route %s: done%n", name);
		if (steps == route_camp) {
			route = null;
			arrived = true;
		}
	}

	/* runs a PathWalker call with its console output dropped; false when the walker could not run */
	private boolean walker_call(Runnable call) {
		PrintStream out = System.out;
		PrintStream err = System.err;
		ByteArrayOutputStream said = new ByteArrayOutputStream();
		PrintStream quiet = new PrintStream(said, true);
		System.setOut(quiet);
		System.setErr(quiet);
		try {
			call.run();
			return !said.toString().contains("Failed to read map");
		} catch (RuntimeException e) {
			return false;
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
	}

	/*
	 * Walks APOS's PathWalker to the tile of the route nearest us on our floor that it has a path to,
	 * trying the next nearest only when it has none; the walk itself is the walker's.
	 */
	private void far_walk(final Step[] steps, String name) {
		if (walker == null) {
			walker = new PathWalker(ex);
			if (!walker_call(new Runnable() {
				@Override
				public void run() {
					walker.init(null);
				}
			})) {
				stop("No way to the route");
				return;
			}
		}
		if (far_route != steps) {
			far_tile = null;
			far_tried.clear();
			far_route = steps;
		}
		if (far_tile != null) {
			final boolean[] walking = new boolean[1];
			if (!walker_call(new Runnable() {
				@Override
				public void run() {
					walking[0] = walker.walkPath();
				}
			})) {
				stop("No way to the route");
				return;
			}
			if (walking[0]) return;
		}
		/* one path search a tick: it can take the client a while */
		final int[] t;
		if (far_tile != null) {
			/* the walker's path is done and the route is still too far: the same tile's path again */
			t = far_tile;
		} else {
			List<int[]> left = far_tiles(steps);
			if (left.isEmpty()) {
				stop("No way to the route");
				return;
			}
			t = left.get(0);
			far_tried.add(key(t[0], t[1]));
		}
		final boolean[] found = new boolean[1];
		if (!walker_call(new Runnable() {
			@Override
			public void run() {
				PathWalker.Path p = walker.calcPath(getX(), getY(), t[0], t[1]);
				walker.setPath(p);
				found[0] = p != null;
			}
		})) {
			stop("No way to the route");
			return;
		}
		if (!found[0]) {
			far_tile = null;
			return;
		}
		if (far_tile == null) System.out.printf("Route %s: too far to resume, walking to %d,%d%n", name, t[0], t[1]);
		far_tile = t;
	}

	/* the route's untried tiles on our floor, steps and landings, nearest first */
	private List<int[]> far_tiles(Step[] steps) {
		List<int[]> out = new ArrayList<int[]>();
		for (Step s : steps) {
			add_far_tile(out, s.x, s.y);
			if (s.lands()) add_far_tile(out, s.land_x, s.land_y);
		}
		Collections.sort(out, new Comparator<int[]>() {
			@Override
			public int compare(int[] a, int[] b) {
				return Integer.compare(tiles(a[0], a[1]), tiles(b[0], b[1]));
			}
		});
		return out;
	}

	private void add_far_tile(List<int[]> out, int x, int y) {
		if (floor_tiles(x, y) == Integer.MAX_VALUE || far_tried.contains(key(x, y))) return;
		for (int[] t : out) {
			if (t[0] == x && t[1] == y) return;
		}
		out.add(new int[]{x, y});
	}

	private static long key(int x, int y) {
		return ((long) x << 32) | (y & 0xffffffffL);
	}

	/*
	 * The step nearest the player on its floor within RESUME_TILES that the client's walking reaches,
	 * a landing standing for the step after it, the later on a tie; -1 when there is none.
	 */
	private int resume(Step[] steps) {
		int best = -1;
		int best_tiles = RESUME_TILES;
		for (int i = 0; i < steps.length; i++) {
			Step s = steps[i];
			int d = floor_tiles(s.x, s.y);
			if (d <= best_tiles && sent(s) && reaches(s)) {
				best = i;
				best_tiles = d;
			}
			if (s.lands()) {
				d = floor_tiles(s.land_x, s.land_y);
				if (d <= best_tiles && isReachable(s.land_x, s.land_y)) {
					best = i + 1;
					best_tiles = d;
				}
			}
		}
		return best;
	}

	/* the client has been sent the step's object or wall; a walk step has none */
	private boolean sent(Step s) {
		if (s.kind == Step.WALK) return true;
		boolean scenery = s.kind == Step.GATE || s.kind == Step.OBJ || s.kind == Step.USEOBJ;
		return scenery ? getObjectIdFromCoords(s.x, s.y) != -1 : wall_id(s.x, s.y, s.dir) != -1;
	}

	/* the client's walking reaches the step's tile, or a tile from which its object or wall is used */
	private boolean reaches(Step s) {
		if (s.kind == Step.WALK) return isReachable(s.x, s.y);
		if (!loaded(s.x, s.y)) return false;
		int[] a = use_area(s);
		return reaches_area(a[0] - ex.getAreaX(), a[1] - ex.getAreaY(), a[2] - ex.getAreaX(), a[3] - ex.getAreaY(),
			a[4] == 1);
	}

	/*
	 * The area the stock client walks to for the step's object or wall, by the object's size, turn and
	 * type and the wall's side: min x, min y, max x, max y, and 1 when it walks beside the area.
	 */
	private int[] use_area(Step s) {
		int x = s.x;
		int y = s.y;
		if (s.kind != Step.GATE && s.kind != Step.OBJ && s.kind != Step.USEOBJ) {
			if (s.dir == 0) return new int[]{x, y - 1, x, y, 0};
			if (s.dir == 1) return new int[]{x - 1, y, x, y, 0};
			return new int[]{x, y, x, y, 1};
		}
		int count = getObjectCount();
		for (int i = 0; i < count; i++) {
			if (getObjectX(i) != x || getObjectY(i) != y) continue;
			int id = ex.getObjectId(i);
			int dir = ex.getObjectDir(i);
			int w = dir == 0 || dir == 4 ? f.f[id] : ub.g[id];
			int h = dir == 0 || dir == 4 ? ub.g[id] : f.f[id];
			if (mb.a[id] != 2 && mb.a[id] != 3) {
				return new int[]{x, y, x + w - 1, y + h - 1, 1};
			}
			if (dir == 0) {
				w++;
				x--;
			}
			if (dir == 2) h++;
			if (dir == 6) {
				y--;
				h++;
			}
			if (dir == 4) w++;
			return new int[]{x, y, x + w - 1, y + h - 1, 0};
		}
		return new int[]{x, y, x, y, 1};
	}

	private boolean reaches_area(int min_x, int min_y, int max_x, int max_y, boolean beside) {
		return route_calc.calculate(ex.getAdjacency(), ex.getLocalX(), ex.getLocalY(), min_x, min_y, max_x, max_y,
			beside) != -1;
	}

	/*
	 * Before a click or use on the step's object or wall: walks round the block NPCs to where it is
	 * used from, or attacks the one in the way; true when it did, and the click waits.
	 */
	private boolean approach(Step s) {
		int[] a = use_area(s);
		return way_round(a[0], a[1], a[2], a[3], a[4] == 1, false);
	}

	/* the last door, gate or cut step before step i, or -1 */
	private static int door_before(Step[] steps, int i) {
		for (int j = i - 1; j >= 0; j--) {
			int k = steps[j].kind;
			if (k == Step.DOOR || k == Step.GATE || k == Step.CUT) return j;
		}
		return -1;
	}

	private boolean landed(Step s) {
		if (s.lands_on_its_floor()) {
			return getX() == s.land_x && getY() == s.land_y;
		}
		return tiles(s.land_x, s.land_y) <= 1;
	}

	/* the last door, gate or cut step before the current one is shut */
	private boolean shut_behind() {
		int door = door_before(route, step);
		return door != -1 && shut(route[door]);
	}

	/* DONE, WAIT, or BACK to the shut door before it when the step cannot be reached */
	private int run_step(Step s) {
		switch (s.kind) {
			case Step.WALK: {
				if (tiles(s.x, s.y) <= STEP_TILES) return DONE;
				if (!isReachable(s.x, s.y)) return shut_behind() ? BACK : WAIT;
				walk(s.x, s.y);
				return WAIT;
			}
			case Step.DOOR:
			case Step.GATE:
				if (!shut(s)) return DONE;
				if (!reaches(s) && shut_behind()) return BACK;
				if (approach(s)) return WAIT;
				if (s.kind == Step.DOOR) {
					click_wall(s.x, s.y, s.dir, 1);
				} else {
					atObject(s.x, s.y);
				}
				return WAIT;
			case Step.CUT:
				if (!shut(s)) return DONE;
				if (!reaches(s) && shut_behind()) return BACK;
				if (s.item == 0) {
					if (!approach(s)) click_wall(s.x, s.y, s.dir, 1);
				} else {
					int slot = use_slot(s);
					if (slot != -1 && !approach(s)) use_on_wall(s.x, s.y, s.dir, slot);
				}
				return WAIT;
			case Step.OBJ:
				if (landed(s)) return DONE;
				if (!reaches(s) && shut_behind()) return BACK;
				if (getObjectIdFromCoords(s.x, s.y) != -1 && !approach(s)) {
					if (s.cmd == 2) {
						atObject2(s.x, s.y);
					} else {
						atObject(s.x, s.y);
					}
				}
				return WAIT;
			case Step.WALL:
				if (landed(s)) return DONE;
				if (!reaches(s) && shut_behind()) return BACK;
				if (wall_id(s.x, s.y, s.dir) != -1 && !approach(s)) click_wall(s.x, s.y, s.dir, s.cmd);
				return WAIT;
			case Step.USEWALL: {
				if (landed(s)) return DONE;
				if (!reaches(s) && shut_behind()) return BACK;
				int slot = use_slot(s);
				if (slot != -1 && wall_id(s.x, s.y, s.dir) != -1 && !approach(s)) {
					use_on_wall(s.x, s.y, s.dir, slot);
				}
				return WAIT;
			}
			case Step.USEOBJ: {
				if (landed(s)) return DONE;
				if (!reaches(s) && shut_behind()) return BACK;
				int slot = use_slot(s);
				if (slot != -1 && getObjectIdFromCoords(s.x, s.y) != -1 && !approach(s)) {
					useSlotOnObject(slot, s.x, s.y);
				}
				return WAIT;
			}
			default:
				return WAIT;
		}
	}

	/* the inventory slot of the step's item; stops the script when it is not carried */
	private int use_slot(Step s) {
		int slot = getInventoryIndex(s.item);
		if (slot == -1) {
			stop(String.format("Route %s step %d needs %s", route_name, step + 1, getItemNameId(s.item)));
		}
		return slot;
	}

	private boolean shut(Step s) {
		if (s.kind == Step.DOOR || s.kind == Step.CUT) return wall_id(s.x, s.y, s.dir) == s.id;
		if (s.kind == Step.GATE) return getObjectIdFromCoords(s.x, s.y) == s.id;
		return false;
	}

	/* the wall object on that side of the tile, or -1 */
	private int wall_id(int x, int y, int dir) {
		int count = getWallObjectCount();
		for (int i = 0; i < count; i++) {
			if (getWallObjectX(i) == x && getWallObjectY(i) == y && ex.getBoundDir(i) == dir) {
				return getWallObjectId(i);
			}
		}
		return -1;
	}

	/* useItemOnWallObject, but for the wall on the given side: a tile can have two */
	private void use_on_wall(int x, int y, int dir, int slot) {
		ex.walkToBound(x - ex.getAreaX(), y - ex.getAreaY(), dir);
		ex.createPacket(Constants.OP_BOUND_USEWITH);
		ex.put2(x);
		ex.put2(y);
		ex.put1(dir);
		ex.put2(slot);
		ex.finishPacket();
	}

	/* atWallObject, but for the wall on the given side: a tile can have two */
	private void click_wall(int x, int y, int dir, int cmd) {
		ex.walkToBound(x - ex.getAreaX(), y - ex.getAreaY(), dir);
		ex.createPacket(cmd == 2 ? Constants.OP_BOUND_ACTION2 : Constants.OP_BOUND_ACTION1);
		ex.put2(x);
		ex.put2(y);
		ex.put1(dir);
		ex.finishPacket();
	}

	/* the item's tile, or a tile beside it in x or z with no wall between, is reached by the client's walking */
	private boolean pick_up_reaches(int x, int y) {
		if (!loaded(x, y)) return false;
		int lx = x - ex.getAreaX();
		int ly = y - ex.getAreaY();
		return reaches_area(lx, ly, lx, ly, true);
	}

	/*
	 * Picks up the loot the inventory has room for on the player's tile, else the nearest in the camp
	 * within LOOT_TILES that the client's walking reaches; none while the hunted target is due soon at
	 * the spawn the player stands by.
	 */
	private boolean loot() {
		if (hunted != null && due_soon_here(hunted)) return false;
		int best = -1;
		int count = getGroundItemCount();
		for (int i = 0; i < count; i++) {
			int id = getGroundItemId(i);
			if (!inArray(pickup_ids, id)) continue;
			if (getEmptySlots() == 0 && !(isItemStackableId(id) && getInventoryIndex(id) != -1)) continue;
			int x = getItemX(i);
			int y = getItemY(i);
			boolean own = x == getX() && y == getY();
			if (!own && (!in_camp(x, y) || tiles(x, y) > LOOT_TILES)) continue;
			if (best != -1 && !before(x, y, id, getItemX(best), getItemY(best), getGroundItemId(best))) continue;
			if (!own && !pick_up_reaches(x, y)) continue;
			best = i;
		}
		if (best == -1) return false;
		pickupItem(getGroundItemId(best), getItemX(best), getItemY(best));
		return true;
	}

	private boolean due_soon_here(Target t) {
		return t.pop != 0L && t.pop >= tick && t.pop - tick <= DUE_SOON_TICKS && tiles(t.x, t.y) <= 1;
	}

	/* the target's local index when it is in view, not fighting, in the camp and reached; else -1 */
	private int live(Target t) {
		int i = local_index(t);
		if (i == -1 || isNpcInCombat(i)) return -1;
		int x = npc_x(i);
		int y = npc_y(i);
		return in_camp(x, y) && isReachable(x, y) ? i : -1;
	}

	/* ticks until the target can be fought, a tile counting as a tick of walking */
	private long hunt_ticks(Target t) {
		int i = live(t);
		if (i != -1) return tiles(npc_x(i), npc_y(i));
		if (t.pop != 0L) {
			long walk = tiles(t.x, t.y);
			long wait = Math.max(0L, t.pop - tick);
			return Math.max(walk, wait) + (walk > wait + LATE_TICKS ? UNSURE_TICKS : 0);
		}
		return (t.tile != null ? tiles(t.tile[0], t.tile[1]) : tiles(t.x, t.y)) + UNSURE_TICKS;
	}

	/* the target soonest fought, the earlier listed on a tie, but last's while that is kept */
	private Target choose(Target last) {
		Target best = null;
		long best_ticks = Long.MAX_VALUE;
		for (Target t : targets) {
			if (tick < t.unreachable_until) continue;
			long n = hunt_ticks(t);
			if (n < best_ticks) {
				best = t;
				best_ticks = n;
			}
		}
		if (last != null && last != best && tick >= last.unreachable_until &&
			(hunt_ticks(last) - best_ticks < KEEP_TICKS || due_soon_here(last))) {
			return last;
		}
		return best;
	}

	/*
	 * Attacks the hunted target when it is live; else waits on its spawn, or its tile when it is not
	 * due, or, while the wait at its spawn has passed its respawn, the farthest corner of its wander box.
	 */
	private void hunt() {
		Target last = hunted;
		while (true) {
			Target t = choose(last);
			hunted = t;
			hunt_tile = null;
			if (t == null) return;
			int i = live(t);
			if (i != -1) {
				hunt_tile = new int[]{npc_x(i), npc_y(i)};
				if (tiles(hunt_tile[0], hunt_tile[1]) > APPROACH_TILES) {
					walk(hunt_tile[0], hunt_tile[1]);
				} else {
					sleep_first();
					attackNpc(i);
				}
				return;
			}
			int[] at = new int[]{t.x, t.y};
			if (t.waited > t.respawn_ticks) {
				int[] corner = far_corner(t);
				if (corner != null) at = corner;
			} else if (t.pop == 0L && t.tile != null) {
				at = t.tile;
			}
			boolean here = getX() == at[0] && getY() == at[1];
			boolean spawn = at[0] == t.x && at[1] == t.y;
			int[] dest = here ? null : spawn ? toward(at[0], at[1]) : isReachable(at[0], at[1]) ? at : null;
			if (!here && dest == null && spawn) {
				System.out.printf("No path to #%d's spawn at %d,%d; skipping it for %d ticks%n",
					t.index, t.x, t.y, UNREACHABLE_TICKS);
				t.unreachable_until = tick + UNREACHABLE_TICKS;
				continue;
			}
			camp = t;
			hunt_tile = at;
			if (dest != null && (dest[0] != getX() || dest[1] != getY())) walk(dest[0], dest[1]);
			return;
		}
	}

	/* the corner of the target's wander box farthest from its spawn that the client's walking reaches, or null */
	private int[] far_corner(Target t) {
		int[][] corners = {{t.min_x, t.min_y}, {t.max_x, t.min_y}, {t.min_x, t.max_y}, {t.max_x, t.max_y}};
		int[] best = null;
		int best_tiles = -1;
		for (int[] c : corners) {
			int d = Math.max(Math.abs(c[0] - t.x), Math.abs(c[1] - t.y));
			if (d > best_tiles && isReachable(c[0], c[1])) {
				best = c;
				best_tiles = d;
			}
		}
		return best;
	}

	/*
	 * The tile to walk to for the tile: itself when the client's walking reaches it; beyond the loaded
	 * area, the one nearest it on the line to the player that is in the camp and reached; else null.
	 */
	private int[] toward(int x, int y) {
		if (loaded(x, y)) {
			return isReachable(x, y) ? new int[]{x, y} : null;
		}
		int n = tiles(x, y);
		for (int i = 1; i < n; i++) {
			int tx = x + (getX() - x) * i / n;
			int ty = y + (getY() - y) * i / n;
			if (loaded(tx, ty) && in_camp(tx, ty) && isReachable(tx, ty)) return new int[]{tx, ty};
		}
		return null;
	}

	/*
	 * Walks to the tile round the block NPCs in view, or, with no way round them, attacks the one in
	 * the way; else walks with the client's walking.
	 */
	private void walk(int x, int y) {
		if (!way_round(x, y, x, y, false, true) && isReachable(x, y)) {
			walkTo(x, y);
		}
	}

	/*
	 * Finds the path to the area, or beside it, as the client does, with the tile of each block NPC in
	 * the search box counted as blocked, and sends it as the client sends a walk. With no way round
	 * them, attacks the block NPC not fighting on the first tile of the path the client's own walking
	 * takes. False, sending nothing, when no block NPC is in the box, when there is no way round and
	 * no NPC to attack, or, before a click (walk false), when the player is already there.
	 */
	private boolean way_round(int x1, int y1, int x2, int y2, boolean beside, boolean walk) {
		if (block_ids.length == 0 || !loaded(x1, y1) || !loaded(x2, y2)) return false;
		int area_x = ex.getAreaX();
		int area_y = ex.getAreaY();
		int min_x = Math.max(0, Math.min(getX(), x1) - SEARCH_TILES - area_x);
		int min_y = Math.max(0, Math.min(getY(), y1) - SEARCH_TILES - area_y);
		int w = Math.min(95, Math.max(getX(), x2) + SEARCH_TILES - area_x) - min_x + 1;
		int h = Math.min(95, Math.max(getY(), y2) + SEARCH_TILES - area_y) - min_y + 1;
		int[][] adjacency = ex.getAdjacency();
		int[][] plain = new int[w][h];
		int[][] flags = new int[w][h];
		for (int i = 0; i < w; i++) {
			for (int j = 0; j < h; j++) {
				plain[i][j] = flags[i][j] = adjacency[min_x + i][min_y + j];
			}
		}
		int start_x = getX() - area_x - min_x;
		int start_y = getY() - area_y - min_y;
		int dest_x1 = x1 - area_x - min_x;
		int dest_y1 = y1 - area_y - min_y;
		int dest_x2 = x2 - area_x - min_x;
		int dest_y2 = y2 - area_y - min_y;
		boolean near = false;
		int count = countNpcs();
		for (int i = 0; i < count; i++) {
			if (!inArray(block_ids, getNpcId(i))) continue;
			int nx = npc_x(i) - area_x - min_x;
			int ny = npc_y(i) - area_y - min_y;
			if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
			if (nx == start_x && ny == start_y) continue;
			/* a walk to an NPC's tile walks to that NPC */
			if (walk && nx == dest_x1 && ny == dest_y1) continue;
			flags[nx][ny] |= FULL_BLOCK;
			near = true;
		}
		if (!near) return false;
		int[] path = find_path(flags, start_x, start_y, dest_x1, dest_y1, dest_x2, dest_y2, beside);
		if (path == null) {
			int[] own = find_path(plain, start_x, start_y, dest_x1, dest_y1, dest_x2, dest_y2, beside);
			if (own == null || own[0] == start_x && own[1] == start_y) return false;
			int fx = getX() + Integer.signum(own[0] - start_x);
			int fy = getY() + Integer.signum(own[1] - start_y);
			int npc = blocker_on(fx, fy);
			if (npc == -1) return false;
			attack_bare(npc);
			attacked_blocker = true;
			blocker_index = npc;
			return true;
		}
		if (path.length == 2 && path[0] == start_x && path[1] == start_y) return false;
		/* the turns from the first to the tile, at most 25 after the first, as the client sends them */
		int n = path.length / 2;
		int first_x = path[0] + min_x + area_x;
		int first_y = path[1] + min_y + area_y;
		ex.createPacket(OP_WALK);
		ex.put2(first_x);
		ex.put2(first_y);
		for (int i = 1; i < n && i <= 25; i++) {
			ex.put1(path[2 * i] + min_x + area_x - first_x);
			ex.put1(path[2 * i + 1] + min_y + area_y - first_y);
		}
		ex.finishPacket();
		return true;
	}

	/* the server index of the block NPC not fighting on the tile, the lowest id then index first; -1 for none */
	private int blocker_on(int x, int y) {
		int best = -1;
		int count = countNpcs();
		for (int i = 0; i < count; i++) {
			if (!inArray(block_ids, getNpcId(i)) || isNpcInCombat(i)) continue;
			if (npc_x(i) != x || npc_y(i) != y) continue;
			if (best == -1 || getNpcId(i) < getNpcId(best) ||
				getNpcId(i) == getNpcId(best) && getNpcServerIndex(i) < getNpcServerIndex(best)) {
				best = i;
			}
		}
		return best == -1 ? -1 : getNpcServerIndex(best);
	}

	/*
	 * The stock client's breadth-first search, over flags[x][y]: the tiles where the path turns, from
	 * the first to the first tile it reaches in the area (dest_x1, dest_y1) to (dest_x2, dest_y2), or,
	 * beside, next to it with no wall between, as x, y pairs; null when it cannot be reached. A start
	 * already there gives just the start.
	 */
	private static int[] find_path(int[][] flags, int start_x, int start_y, int dest_x1, int dest_y1, int dest_x2,
		int dest_y2, boolean beside) {
		int w = flags.length;
		int h = flags[0].length;
		int[][] source = new int[w][h];
		int[] qx = new int[w * h];
		int[] qy = new int[w * h];
		int read = 0;
		int write = 0;
		source[start_x][start_y] = 99;
		qx[write] = start_x;
		qy[write++] = start_y;
		int dest_x = -1;
		int dest_y = -1;
		while (read != write) {
			int x = qx[read];
			int y = qy[read++];
			if (x >= dest_x1 && x <= dest_x2 && y >= dest_y1 && y <= dest_y2 ||
				beside && (x > 0 && x - 1 >= dest_x1 && x - 1 <= dest_x2 && y >= dest_y1 && y <= dest_y2 &&
					(flags[x - 1][y] & WALL_WEST) == 0 ||
					x < w - 1 && x + 1 >= dest_x1 && x + 1 <= dest_x2 && y >= dest_y1 && y <= dest_y2 &&
						(flags[x + 1][y] & WALL_EAST) == 0 ||
					y > 0 && x >= dest_x1 && x <= dest_x2 && y - 1 >= dest_y1 && y - 1 <= dest_y2 &&
						(flags[x][y - 1] & WALL_SOUTH) == 0 ||
					y < h - 1 && x >= dest_x1 && x <= dest_x2 && y + 1 >= dest_y1 && y + 1 <= dest_y2 &&
						(flags[x][y + 1] & WALL_NORTH) == 0)) {
				dest_x = x;
				dest_y = y;
				break;
			}
			if (x > 0 && source[x - 1][y] == 0 && (flags[x - 1][y] & (FULL_BLOCK | WALL_WEST)) == 0) {
				qx[write] = x - 1;
				qy[write++] = y;
				source[x - 1][y] = SOURCE_WEST;
			}
			if (x < w - 1 && source[x + 1][y] == 0 && (flags[x + 1][y] & (FULL_BLOCK | WALL_EAST)) == 0) {
				qx[write] = x + 1;
				qy[write++] = y;
				source[x + 1][y] = SOURCE_EAST;
			}
			if (y > 0 && source[x][y - 1] == 0 && (flags[x][y - 1] & (FULL_BLOCK | WALL_SOUTH)) == 0) {
				qx[write] = x;
				qy[write++] = y - 1;
				source[x][y - 1] = SOURCE_SOUTH;
			}
			if (y < h - 1 && source[x][y + 1] == 0 && (flags[x][y + 1] & (FULL_BLOCK | WALL_NORTH)) == 0) {
				qx[write] = x;
				qy[write++] = y + 1;
				source[x][y + 1] = SOURCE_NORTH;
			}
			if (x > 0 && y > 0 && (flags[x][y - 1] & (FULL_BLOCK | WALL_SOUTH)) == 0 &&
				(flags[x - 1][y] & (FULL_BLOCK | WALL_WEST)) == 0 &&
				(flags[x - 1][y - 1] & (FULL_BLOCK | WALL_SOUTH | WALL_WEST)) == 0 &&
				source[x - 1][y - 1] == 0) {
				qx[write] = x - 1;
				qy[write++] = y - 1;
				source[x - 1][y - 1] = SOURCE_SOUTH | SOURCE_WEST;
			}
			if (x < w - 1 && y > 0 && (flags[x][y - 1] & (FULL_BLOCK | WALL_SOUTH)) == 0 &&
				(flags[x + 1][y] & (FULL_BLOCK | WALL_EAST)) == 0 &&
				(flags[x + 1][y - 1] & (FULL_BLOCK | WALL_SOUTH | WALL_EAST)) == 0 &&
				source[x + 1][y - 1] == 0) {
				qx[write] = x + 1;
				qy[write++] = y - 1;
				source[x + 1][y - 1] = SOURCE_SOUTH | SOURCE_EAST;
			}
			if (x > 0 && y < h - 1 && (flags[x][y + 1] & (FULL_BLOCK | WALL_NORTH)) == 0 &&
				(flags[x - 1][y] & (FULL_BLOCK | WALL_WEST)) == 0 &&
				(flags[x - 1][y + 1] & (FULL_BLOCK | WALL_NORTH | WALL_WEST)) == 0 &&
				source[x - 1][y + 1] == 0) {
				qx[write] = x - 1;
				qy[write++] = y + 1;
				source[x - 1][y + 1] = SOURCE_NORTH | SOURCE_WEST;
			}
			if (x < w - 1 && y < h - 1 && (flags[x][y + 1] & (FULL_BLOCK | WALL_NORTH)) == 0 &&
				(flags[x + 1][y] & (FULL_BLOCK | WALL_EAST)) == 0 &&
				(flags[x + 1][y + 1] & (FULL_BLOCK | WALL_NORTH | WALL_EAST)) == 0 &&
				source[x + 1][y + 1] == 0) {
				qx[write] = x + 1;
				qy[write++] = y + 1;
				source[x + 1][y + 1] = SOURCE_NORTH | SOURCE_EAST;
			}
		}
		if (dest_x == -1) return null;
		/* back from the tile, keeping it and each tile where the direction changes */
		List<int[]> turns = new ArrayList<int[]>();
		int x = dest_x;
		int y = dest_y;
		turns.add(new int[]{x, y});
		int prev = source[x][y];
		int src = prev;
		while (x != start_x || y != start_y) {
			if (src != prev) {
				prev = src;
				turns.add(new int[]{x, y});
			}
			if ((src & SOURCE_SOUTH) != 0) {
				y++;
			} else if ((src & SOURCE_NORTH) != 0) {
				y--;
			}
			if ((src & SOURCE_WEST) != 0) {
				x++;
			} else if ((src & SOURCE_EAST) != 0) {
				x--;
			}
			src = source[x][y];
		}
		int[] path = new int[turns.size() * 2];
		for (int i = 0; i < turns.size(); i++) {
			int[] t = turns.get(turns.size() - 1 - i);
			path[2 * i] = t[0];
			path[2 * i + 1] = t[1];
		}
		return path;
	}

	/* inside the client's 96 by 96 tile area; isReachable says no to anything beyond it */
	private boolean loaded(int x, int y) {
		int lx = x - ex.getAreaX();
		int ly = y - ex.getAreaY();
		return lx >= 0 && lx < 96 && ly >= 0 && ly < 96;
	}

	/*
	 * Keeps, for each target, from the server update that put the player on (px, py): when it is due
	 * back, where it was last seen when it walked out of view, and how long it was waited for at its spawn.
	 */
	private void observe(int px, int py) {
		for (Target t : targets) {
			int i = local_index(t);
			if (i != -1) {
				boolean fighting = isNpcInCombat(i);
				t.pop = fighting ? tick + t.respawn_ticks : 0L;
				t.tile = null;
				t.waited = 0L;
				t.seen = new int[]{npc_x(i), npc_y(i)};
				t.seen_fighting = fighting;
				continue;
			}
			boolean died = false;
			boolean left_near = false;
			if (t.seen != null && near(px, py, t.seen[0], t.seen[1])) {
				t.waited = 0L;
				left_near = true;
				died = !t.seen_fighting;
			} else if (t.seen != null && !t.seen_fighting) {
				t.tile = t.seen;
			}
			t.seen = null;
			if (t.tile != null && near(px, py, t.tile[0], t.tile[1])) t.tile = null;
			boolean by_spawn = Math.max(Math.abs(t.x - px), Math.abs(t.y - py)) <= 1;
			if (died) {
				t.pop = tick + t.respawn_ticks;
			} else {
				/* someone else killed it out of sight */
				if ((t.pop == 0L || tick > t.pop + 1) && (by_spawn && t.tile == null || box_near(t, px, py))) {
					t.pop = tick + t.respawn_ticks;
				}
				if (t == hunted && by_spawn && !left_near) t.waited++;
			}
		}
	}

	private void pre_attack() {
		/* the attack on a block NPC in the way is this tick's attack */
		if (camp == null || attacked_blocker || fighting() || local_index(camp) != -1) {
			return;
		}
		pre_attack_tick = tick;
		sleep_first();
		attack_bare(camp.index);
	}

	/*
	 * Uses the sleeping bag, at `sleep` fatigue and awake, just before an attack on a target: the bag
	 * keeps a walk sent before it but drops a pending attack, and the player stays asleep through the
	 * fight it starts
	 * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L84
	 * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/Sleeping.java#L41
	 * https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L771
	 */
	private void sleep_first() {
		if (sleep_at == -1 || ex.isSleeping() || getFatigue() < sleep_at) return;
		int slot = getInventoryIndex(SLEEPING_BAG);
		if (slot != -1) useItem(slot);
	}

	/* an attack with no walk of the client's own before it */
	private void attack_bare(int server_index) {
		ex.createPacket(Constants.OP_NPC_ATTACK);
		ex.put2(server_index);
		ex.finishPacket();
	}

	private static boolean box_near(Target t, int px, int py) {
		return near(px, py, t.min_x, t.min_y) && near(px, py, t.min_x, t.max_y) && near(px, py, t.max_x, t.min_y) &&
			near(px, py, t.max_x, t.max_y);
	}

	private static boolean near(int px, int py, int x, int y) {
		int dx = x - px;
		int dy = y - py;
		return dx * dx + dy * dy <= VIEW_SQ;
	}

	private int tiles(int x, int y) {
		return Math.max(Math.abs(x - getX()), Math.abs(y - getY()));
	}

	/* tiles away on our floor; MAX_VALUE on another floor */
	private int floor_tiles(int x, int y) {
		return y / FLOOR == getY() / FLOOR ? tiles(x, y) : Integer.MAX_VALUE;
	}

	private boolean in_camp(int x, int y) {
		return x >= camp_min_x && x <= camp_max_x && y >= camp_min_y && y <= camp_max_y;
	}

	private boolean in_camp_grown(int x, int y) {
		return x >= camp_min_x - 1 && x <= camp_max_x + 1 && y >= camp_min_y - 1 && y <= camp_max_y + 1;
	}

	/* the tile the server last put the NPC on: the newest of its waypoints, not where it is drawn */
	private int npc_x(int local_index) {
		return mob_x(ex.getNpc(local_index));
	}

	private int npc_y(int local_index) {
		return mob_y(ex.getNpc(local_index));
	}

	private int mob_x(Object npc) {
		ta mob = (ta) npc;
		return (mob.k[mob.o] - 64) / TILE_UNITS + ex.getAreaX();
	}

	private int mob_y(Object npc) {
		ta mob = (ta) npc;
		return (mob.F[mob.o] - 64) / TILE_UNITS + ex.getAreaY();
	}

	/* the target's local index when the client sees an NPC with its server index and id, else -1 */
	private int local_index(Target t) {
		int count = countNpcs();
		for (int i = 0; i < count; i++) {
			if (getNpcServerIndex(i) == t.index && getNpcId(i) == t.id) return i;
		}
		return -1;
	}

	private Target target_of(int server_index, int id) {
		for (Target t : targets) {
			if (t.index == server_index && t.id == id) return t;
		}
		return null;
	}

	/*
	 * The first packet of a server update: the last update is all applied, and is observed here when
	 * main did not decide on it. Asleep, it is decided on here, a tick late and with the player's new
	 * tile: APOS does not run main while the client sleeps (ScriptListener.onRunScript).
	 */
	@Override
	public void onPlayerCoord(int x, int y) {
		long now = currentTimeMillis();
		/* a gap this long is a reconnect: the NPCs the client drops then did not die */
		boolean relog = update_ms != 0L && now - update_ms > RELOG_MS;
		if (update_seen && start_time != -1L && !relog) {
			if (ex.isSleeping() && !stopped) {
				decide(update_x, update_y);
			} else {
				observe(update_x, update_y);
			}
		}
		if (relog) {
			for (Target t : targets) {
				t.seen = null;
			}
			tick += Math.max(1L, (now - update_ms) / tick_ms);
		} else {
			tick++;
		}
		update_ms = now;
		update_seen = true;
		update_x = x;
		update_y = y;
	}

	/* walks to the first neighbouring tile the client's walking reaches; false when there is none */
	private boolean step_aside() {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if ((dx != 0 || dy != 0) && isReachable(getX() + dx, getY() + dy)) {
					walk(getX() + dx, getY() + dy);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onDeath() {
		stop("Died: start it again");
	}

	@Override
	public void onServerMessage(String str) {
		if (str.contains("You have been standing here for")) {
			standing_warned = true;
		}
	}

	@Override
	public void paint() {
		if (start_time == -1L) {
			return;
		}
		paint_spawn_timers();
		int x = (512 / 2) - 50;
		int y = 60;
		drawBoxAlphaFill(x - 8, y - 18, 300, paint_max_y - y, 120, 0x0);
		drawString("Bob_MapCamper", x, y, 2, 0xFFD900);
		y += 15;
		drawString("Runtime: " + get_time_since(start_time),
			x, y, 2, 0xFFFFFF);
		y += 15;
		if (isBanking()) {
			drawString("Banking", x, y, 2, 0xFFFFFF);
			y += 15;
		} else if (route != null) {
			drawString(String.format("Walking to the %s: step %d/%d",
					route_name, Math.min(step + 1, route.length), route.length),
				x, y, 2, 0xFFFFFF);
			y += 15;
		} else if (camp != null && !fighting()) {
			long secs = secs(camp.pop - tick);
			String status = pre_attack_tick != -1L && pre_attack_tick >= tick - 1 ? "Pre-attacking" :
				getX() == camp.x && getY() == camp.y ? "Holding" : "Going to";
			drawString(String.format("%s %s #%d (%d,%d), up in %ds",
					status, getNpcNameId(camp.id), camp.index, camp.x, camp.y, secs),
				x, y, 2, 0xFFFFFF);
			y += 15;
		}
		for (int i = 0; i < start_xp.length; ++i) {
			int gained = getXpForLevel(i) - start_xp[i];
			if (gained == 0) continue;
			drawString(String.format("%s XP gained: %s (%s/h)",
					SKILL[i], iformat.format(gained), per_hour(gained)),
				x, y, 2, 0xFFFFFF);
			y += 15;
		}
		for (int i = 0; i < loot_ids.length; ++i) {
			if (banked_count[i] == 0) continue;
			drawString(String.format("%s banked: %s (%s/h)",
					getItemNameId(loot_ids[i]),
					iformat.format(banked_count[i]),
					per_hour(banked_count[i])),
				x, y, 2, 0xFFFFFF);
			y += 15;
		}
		paint_max_y = y + 15;
	}

	private void paint_spawn_timers() {
		for (Target t : targets) {
			if (tiles(t.x, t.y) > LABEL_TILES || local_index(t) != -1) continue;
			int[] p = tile_to_screen(t.x, t.y);
			if (p == null) continue;
			String label;
			int colour;
			if (t.pop == 0L) {
				label = "?";
				colour = 0xA0A0A0;
			} else {
				long secs = secs(t.pop - tick);
				label = secs + "s";
				colour = secs <= 3 ? 0x00FF00 : 0xFFFF00;
			}
			drawString(label, p[0] - label.length() * 3, p[1], 1, colour);
		}
	}

	private int[] tile_to_screen(int tile_x, int tile_y) {
		int look_x = CameraHandler.lookAtX + CameraHandler.rotationY;
		int look_z = CameraHandler.lookAtY + ex.getCameraNSOffset();
		int pitch = 912;
		int yaw = (CameraHandler.rotation * 4) & 1023;
		int ox = 0, oy, oz = CameraHandler.zoom * 2;
		int s = SIN[pitch], c = SIN[pitch + 1024], t;
		t = -oz * s >> 15;
		oz = oz * c >> 15;
		oy = t;
		if (yaw != 0) {
			s = SIN[yaw];
			c = SIN[yaw + 1024];
			t = oz * s >> 15;
			oz = oz * c >> 15;
			ox = t;
		}
		int px = (tile_x - ex.getAreaX()) * 128 + 64;
		int pz = (tile_y - ex.getAreaY()) * 128 + 64;
		int dx = px - (look_x - ox);
		int dy = -ex.Hh.f(px, pz, 105) - (-ex.Hh.f(look_x, look_z, 105) - oy);
		int dz = pz - (look_z - oz);
		int view_yaw = (1024 - yaw) & 1023;
		if (view_yaw != 0) {
			s = SIN[view_yaw];
			c = SIN[view_yaw + 1024];
			t = dz * s + dx * c >> 15;
			dz = dz * c - dx * s >> 15;
			dx = t;
		}
		int view_pitch = (1024 - pitch) & 1023;
		s = SIN[view_pitch];
		c = SIN[view_pitch + 1024];
		t = dy * c - dz * s >> 15;
		dz = dy * s + dz * c >> 15;
		dy = t;
		if (dz < 50) return null;
		int shift = ex.Ek.R;
		int view_w = ex.getGameWidth(), view_h = ex.getGameHeight() - 12;
		int sx = view_w / 2 + (dx << shift) / dz;
		int sy = view_h / 2 + (dy << shift) / dz;
		if (sx < 0 || sx >= view_w || sy < 0 || sy >= view_h) return null;
		return new int[]{sx, sy};
	}

	private long secs(long ticks) {
		return (Math.max(0L, ticks) * tick_ms + 999) / 1000;
	}

	private String per_hour(int count) {
		double amount, secs;

		if (count == 0) return "0";
		amount = count * 60.0 * 60.0;
		secs = (currentTimeMillis() - start_time) / 1000.0;
		return iformat.format(amount / secs);
	}

	private static String get_time_since(long t) {
		long millis = (currentTimeMillis() - t) / 1000;
		long second = millis % 60;
		long minute = (millis / 60) % 60;
		long hour = (millis / (60 * 60)) % 24;
		long day = (millis / (60 * 60 * 24));

		if (day > 0L) {
			return String.format("%02d days, %02d hrs, %02d mins",
				day, hour, minute);
		}
		if (hour > 0L) {
			return String.format("%02d hours, %02d mins, %02d secs",
				hour, minute, second);
		}
		if (minute > 0L) {
			return String.format("%02d minutes, %02d seconds",
				minute, second);
		}
		return String.format("%02d seconds", second);
	}

	private static final class Target {
		final int index, id, x, y, min_x, min_y, max_x, max_y;
		final long respawn_ticks;
		/* the tick it is due back; 0 means it may be up right now */
		long pop;
		/* no path to its spawn tile: do not wait there before this tick */
		long unreachable_until;
		/* where it was last seen when it walked out of view, or null */
		int[] tile;
		/* updates spent hunting it within a tile of its spawn, out of view, since it was last in view or left it near */
		long waited;
		/* its tile in the last update observed, null when it was not in view, and whether it was fighting */
		int[] seen;
		boolean seen_fighting;

		Target(int index, int id, int x, int y, long respawn_ticks, int min_x, int min_y, int max_x, int max_y) {
			this.index = index;
			this.id = id;
			this.x = x;
			this.y = y;
			this.respawn_ticks = respawn_ticks;
			this.min_x = min_x;
			this.min_y = min_y;
			this.max_x = max_x;
			this.max_y = max_y;
		}
	}

	private static final class Item {
		final int id, min, target;
		final boolean wear;

		Item(int id, int min, int target, boolean wear) {
			this.id = id;
			this.min = min;
			this.target = target;
			this.wear = wear;
		}
	}

	private static final class Step {
		static final int WALK = 0, DOOR = 1, GATE = 2, OBJ = 3, WALL = 4, CUT = 5, USEWALL = 6, USEOBJ = 7;

		/* id: a door's, gate's or web's shut id; item: what a cut or use step uses, 0 to click */
		final int kind, x, y, dir, id, cmd, item, land_x, land_y, line;
		final String text;

		Step(int kind, int x, int y, int dir, int id, int cmd, int item, int land_x, int land_y, int line, String text) {
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.dir = dir;
			this.id = id;
			this.cmd = cmd;
			this.item = item;
			this.land_x = land_x;
			this.land_y = land_y;
			this.line = line;
			this.text = text;
		}

		boolean lands() {
			return kind == OBJ || kind == WALL || kind == USEWALL || kind == USEOBJ;
		}

		boolean lands_on_its_floor() {
			return land_y / FLOOR == y / FLOOR;
		}

		boolean uses_item() {
			return kind == USEWALL || kind == USEOBJ || kind == CUT && item != 0;
		}
	}

	/* The camp recipe, read line by line. Nothing in here touches the client. */
	private static final class Recipe {
		String world;
		long tick_ms = 640L;
		final List<Target> targets = new ArrayList<Target>();
		boolean has_camp;
		int camp_x, camp_y, camp_min_x, camp_min_y, camp_max_x, camp_max_y;
		boolean has_bank_line;
		int bank_x, bank_y;
		int style = -1;
		int eat_at;
		int sleep_at = -1;
		int sleep_line;
		int[] food_ids = new int[0];
		final List<Item> items = new ArrayList<Item>();
		int[] loot_ids = new int[0];
		int[] bury_ids = new int[0];
		int[] block_ids = new int[0];
		Step[] route_bank;
		Step[] route_camp;

		private static final List<String> SETTINGS =
			Arrays.asList("world", "camp", "bank", "style", "eat", "sleep", "food", "loot", "bury", "block");

		/* each setting, target index, item id and route given, and its line */
		private final Map<String, Integer> given = new HashMap<String, Integer>();
		private List<Step> open;
		private String open_name;
		private int open_line;

		/* the recipe, or IllegalArgumentException("line <n>: <what is wrong>") */
		static Recipe parse(String[] lines) {
			Recipe r = new Recipe();
			int last = 0;
			for (int n = 0; n < lines.length; n++) {
				String line = lines[n];
				if (!line.trim().isEmpty()) last = n + 1;
				int hash = line.indexOf('#');
				if (hash != -1) line = line.substring(0, hash);
				line = line.trim();
				if (line.isEmpty()) continue;
				try {
					r.line(line.split("\\s+"), n + 1, line);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("line " + (n + 1) + ": " + e.getMessage());
				}
			}
			if (r.open != null) {
				throw new IllegalArgumentException("line " + r.open_line + ": route " + r.open_name + " has no end");
			}
			String missing = r.targets.isEmpty() ? "target" : !r.has_camp ? "camp" : !r.has_bank_line ? "bank" :
				r.route_bank == null ? "route bank" : r.route_camp == null ? "route camp" : null;
			if (missing != null) throw new IllegalArgumentException("line " + last + ": no " + missing + " line");
			for (Step[] route : new Step[][]{r.route_bank, r.route_camp}) {
				for (Step s : route) {
					if (s.uses_item() && !r.carries(s.item)) {
						throw new IllegalArgumentException("line " + s.line + ": " + s.text.split("\\s+")[0] +
							" needs an item line for " + s.item + " with min 1 or more");
					}
				}
			}
			if (r.sleep_at != -1 && !r.carries(SLEEPING_BAG)) {
				throw new IllegalArgumentException("line " + r.sleep_line + ": sleep needs an item line for " +
					SLEEPING_BAG + " with min 1 or more");
			}
			return r;
		}

		/* the recipe's title: its first comment line, before any instruction */
		static String title(String text) {
			for (String line : text.split("\\r?\\n")) {
				String t = line.trim();
				if (t.isEmpty()) continue;
				if (!t.startsWith("#")) break;
				t = t.substring(1).trim();
				if (!t.isEmpty()) return t;
			}
			return "Untitled";
		}

		/* a file name for the title, safe on every system */
		static String file_name(String title) {
			String name = title.replaceAll("[^A-Za-z0-9 _.,@()+-]", "_").replaceAll("\\s+", " ").trim();
			name = name.replaceAll("^[ .]+|[ .]+$", "");
			if (name.length() > 100) name = name.substring(0, 100).trim();
			return (name.isEmpty() ? "Untitled" : name) + ".txt";
		}

		/* what two names must share to be the same recipe: letters and digits, any case */
		static String key(String name) {
			return name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]", "");
		}

		private boolean carries(int id) {
			for (Item it : items) {
				if (it.id == id && it.min >= 1) return true;
			}
			return false;
		}

		/* what was given on line_no; refused, naming its first word, when it was given before */
		private void once(String what, String word, int line_no) {
			Integer was = given.get(what);
			if (was != null) throw new IllegalArgumentException(word + " is already on line " + was);
			given.put(what, line_no);
		}

		private void step(int kind, int x, int y, int dir, int id, int cmd, int item, int land_x, int land_y,
						  int line_no, String text) {
			open.add(new Step(kind, x, y, dir, id, cmd, item, land_x, land_y, line_no, text));
		}

		private void line(String[] w, int line_no, String text) {
			String k = w[0];
			if (open != null) {
				if (k.equals("end")) {
					args(w, 0);
					Step[] route = open.toArray(new Step[0]);
					if (route.length == 0) throw new IllegalArgumentException("route " + open_name + " has no steps");
					if (open_name.equals("bank")) route_bank = route;
					else route_camp = route;
					open = null;
				} else if (k.equals("walk")) {
					args(w, 2);
					step(Step.WALK, num(w[1]), num(w[2]), 0, 0, 0, 0, 0, 0, line_no, text);
				} else if (k.equals("door")) {
					args(w, 4);
					step(Step.DOOR, num(w[1]), num(w[2]), range("dir", w[3], 0, 3), num(w[4]), 0, 0, 0, 0,
						line_no, text);
				} else if (k.equals("gate")) {
					args(w, 3);
					step(Step.GATE, num(w[1]), num(w[2]), 0, num(w[3]), 0, 0, 0, 0, line_no, text);
				} else if (k.equals("obj")) {
					args(w, 5);
					step(Step.OBJ, num(w[1]), num(w[2]), 0, 0, range("cmd", w[3], 1, 2), 0, num(w[4]), num(w[5]),
						line_no, text);
				} else if (k.equals("wall")) {
					args(w, 6);
					step(Step.WALL, num(w[1]), num(w[2]), range("dir", w[3], 0, 3), 0, range("cmd", w[4], 1, 2), 0,
						num(w[5]), num(w[6]), line_no, text);
				} else if (k.equals("cut")) {
					args(w, 5);
					step(Step.CUT, num(w[1]), num(w[2]), range("dir", w[3], 0, 3), num(w[4]), 0, num(w[5]), 0, 0,
						line_no, text);
				} else if (k.equals("usewall")) {
					args(w, 6);
					step(Step.USEWALL, num(w[1]), num(w[2]), range("dir", w[3], 0, 3), 0, 0,
						range("itemId", w[4], 1, Integer.MAX_VALUE), num(w[5]), num(w[6]), line_no, text);
				} else if (k.equals("useobj")) {
					args(w, 5);
					step(Step.USEOBJ, num(w[1]), num(w[2]), 0, 0, 0, range("itemId", w[3], 1, Integer.MAX_VALUE),
						num(w[4]), num(w[5]), line_no, text);
				} else {
					throw new IllegalArgumentException("unknown instruction " + k);
				}
				return;
			}
			if (SETTINGS.contains(k)) once(k, k, line_no);
			if (k.equals("world")) {
				if (w.length != 3) throw new IllegalArgumentException("world takes 1 numbers");
				tick_ms = range("world", w[2], 1, Integer.MAX_VALUE);
				world = w[1];
			} else if (k.equals("target")) {
				args(w, 9);
				Target t = new Target(num(w[1]), num(w[2]), num(w[3]), num(w[4]), num(w[5]),
					num(w[6]), num(w[7]), num(w[8]), num(w[9]));
				once("target " + t.index, k, line_no);
				box(t.min_x, t.min_y, t.max_x, t.max_y);
				targets.add(t);
			} else if (k.equals("camp")) {
				args(w, 6);
				camp_x = num(w[1]);
				camp_y = num(w[2]);
				camp_min_x = num(w[3]);
				camp_min_y = num(w[4]);
				camp_max_x = num(w[5]);
				camp_max_y = num(w[6]);
				box(camp_min_x, camp_min_y, camp_max_x, camp_max_y);
				has_camp = true;
			} else if (k.equals("bank")) {
				args(w, 2);
				bank_x = num(w[1]);
				bank_y = num(w[2]);
				has_bank_line = true;
			} else if (k.equals("style")) {
				args(w, 1);
				style = range("style", w[1], 0, 3);
			} else if (k.equals("eat")) {
				args(w, 1);
				eat_at = num(w[1]);
			} else if (k.equals("sleep")) {
				args(w, 1);
				sleep_at = range("sleep", w[1], 1, 100);
				sleep_line = line_no;
			} else if (k.equals("food")) {
				food_ids = ids(w);
			} else if (k.equals("item")) {
				boolean wear = w.length == 5 && w[4].equals("wear");
				args(wear ? Arrays.copyOf(w, 4) : w, 3);
				int id = num(w[1]);
				int min = num(w[2]);
				int target = num(w[3]);
				once("item " + id, k, line_no);
				if (min > target) throw new IllegalArgumentException("min is above target");
				items.add(new Item(id, min, target, wear));
			} else if (k.equals("loot")) {
				loot_ids = ids(w);
			} else if (k.equals("bury")) {
				bury_ids = ids(w);
			} else if (k.equals("block")) {
				block_ids = ids(w);
			} else if (k.equals("route") && w.length == 2 && (w[1].equals("bank") || w[1].equals("camp"))) {
				once("route " + w[1], k, line_no);
				open = new ArrayList<Step>();
				open_name = w[1];
				open_line = line_no;
			} else {
				throw new IllegalArgumentException("unknown instruction " + k);
			}
		}

		private static void box(int min_x, int min_y, int max_x, int max_y) {
			if (min_x > max_x || min_y > max_y) throw new IllegalArgumentException("the box's min is above its max");
		}

		private static void args(String[] w, int n) {
			if (w.length - 1 != n) throw new IllegalArgumentException(w[0] + " takes " + n + " numbers");
		}

		private static int[] ids(String[] w) {
			int[] ids = new int[w.length - 1];
			for (int i = 1; i < w.length; i++) {
				ids[i - 1] = num(w[i]);
			}
			return ids;
		}

		/* a whole number from 0 to 2147483647 */
		private static int num(String s) {
			String digits = s.replaceFirst("^0+(?=.)", "");
			if (!s.matches("[0-9]+") || digits.length() > 10 || Long.parseLong(digits) > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(s + " is not a number");
			}
			return Integer.parseInt(digits);
		}

		private static int range(String name, String s, int min, int max) {
			int n = num(s);
			if (n < min || n > max) throw new IllegalArgumentException(name + " is " + min + " to " + max);
			return n;
		}
	}
}
