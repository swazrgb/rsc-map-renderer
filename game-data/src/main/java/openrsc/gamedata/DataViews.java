package openrsc.gamedata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure projections of the game-data model into the shapes the web/viewer API
 * exposes — the single source of truth shared by the live server controllers
 * AND the static-asset bakers, so both emit byte-identical output and a field
 * added here updates every consumer at once. No Spring, no framework.
 */
public final class DataViews {

  /** Server FLOOR_HEIGHT: folded z / this = floor, folded z % this = local z. */
  private static final int FLOOR_HEIGHT = 944;

  /** One static NPC spawn: load-order serverIndex, def id, resolved name, tile. */
  public record SpawnDto(int serverIndex, int id, String name, int x, int z) {}

  /** One fixed scenery placement: def id, facing, tile x, floor-local z, floor. */
  public record SceneryDto(int id, int dir, int x, int z, int floor) {}

  /** The full static NPC spawn table (load order = stable serverIndex), names
   *  resolved from the npc defs. */
  public static List<SpawnDto> spawns(List<NpcLocs.Spawn> locs, NpcDefs defs) {
    List<SpawnDto> out = new ArrayList<>(locs.size());
    for (int i = 0; i < locs.size(); i++) {
      NpcLocs.Spawn s = locs.get(i);
      out.add(new SpawnDto(i, s.id(), defs == null ? null : defs.nameOf(s.id()),
          s.start().x(), s.start().z()));
    }
    return out;
  }

  /** Every fixed scenery placement, with the floor split out of the folded z. */
  public static List<SceneryDto> scenery(List<SceneryLocs.Loc> locs) {
    List<SceneryDto> out = new ArrayList<>(locs.size());
    for (SceneryLocs.Loc loc : locs) {
      int y = loc.pos().y();
      out.add(new SceneryDto(loc.id(), loc.direction(), loc.pos().x(),
          y % FLOOR_HEIGHT, y / FLOOR_HEIGHT));
    }
    return out;
  }

  /** appearance-sprite id → the item names worn as it (a sprite can be shared,
   *  e.g. every metal's blades look alike), for the equipped-item hover card. */
  public static Map<Integer, List<String>> wearables(ItemDefs defs) {
    Map<Integer, List<String>> out = new LinkedHashMap<>();
    if (defs == null) {
      return out;
    }
    for (int id = 0; id < defs.size(); id++) {
      ItemDefs.Entry e = defs.get(id);
      if (e == null || e.isWearable() == 0 || e.appearanceID() <= 0) {
        continue;
      }
      List<String> names = out.computeIfAbsent(e.appearanceID(), k -> new ArrayList<>());
      if (!names.contains(e.name())) {
        names.add(e.name());
      }
    }
    return out;
  }

  private DataViews() {}
}
