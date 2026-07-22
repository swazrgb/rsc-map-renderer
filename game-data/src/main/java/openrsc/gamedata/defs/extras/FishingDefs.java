package openrsc.gamedata.defs.extras;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ObjectFishing.xml} — fishing-spot scenery id → per-command spot defs. Mirrors
 * {@code EntityHandler.objectFishing} ({@code HashMap<Integer, ObjectFishingDef[]>}): the array is
 * indexed by the scenery's <em>command index</em> (0 = command1, 1 = command2 — e.g. spot 192 is
 * "lure"/"bait", so index 0 is the lure table and index 1 the bait table), exactly as
 * {@code EntityHandler.getObjectFishingDef(id, click)} consumes it.
 *
 * <p>Each {@link Spot} carries an <b>ordered</b> fish list — the XML is
 * explicitly ordered highest required level first ("DO NOT REARRANGE THE ORDER OF THE FISH"),
 * because the server's catch roll walks the array in document order. Document order is preserved
 * here.
 */
public record FishingDefs(Map<Integer, List<Spot>> bySceneryId) {

  /**
   * One fish that can be caught at a spot — server {@code ObjectFishDef}.
   */
  public record Fish(int fishId, int requiredLevel, int exp, int lowRate, int highRate) {

  }

  /**
   * One (sceneryId, commandIndex) fishing table — server {@code ObjectFishingDef}. {@code cascade}
   * = whether the fish roll against each other (trout/salmon).
   */
  public record Spot(int netId, int baitId, int depletion, int respawnTime,
                     int cascade, List<Fish> fish) {

  }

  public static FishingDefs load(Path file) {
    Map<Integer, List<Spot>> m = new HashMap<>();
    ExtrasXml.eachEntry(file, (id, arr) -> {
      List<Spot> spots = new ArrayList<>();
      for (XmlTree s : arr.children("ObjectFishingDef")) {
        List<Fish> fish = new ArrayList<>();
        XmlTree defs = s.child("defs");
        if (defs != null) {
          for (XmlTree f : defs.children("ObjectFishDef")) {
            fish.add(new Fish(
                f.intOf("fishId", 0),
                f.intOf("requiredLevel", 0),
                f.intOf("exp", 0),
                f.intOf("lowRate", 0),
                f.intOf("highRate", 0)));
          }
        }
        spots.add(new Spot(
            s.intOf("netId", 0),
            s.intOf("baitId", 0),
            s.intOf("depletion", 0),
            s.intOf("respawnTime", 0),
            s.intOf("cascade", 0),
            List.copyOf(fish)));
      }
      m.put(id, List.copyOf(spots));
    });
    return new FishingDefs(Map.copyOf(m));
  }

  /**
   * Server-parity lookup: {@code EntityHandler.getObjectFishingDef(id, click)}. Returns
   * {@code null} if the scenery isn't a fishing spot or the command index is out of range.
   */
  public Spot get(int sceneryId, int commandIndex) {
    List<Spot> spots = bySceneryId.get(sceneryId);
    if (spots == null || commandIndex < 0 || commandIndex >= spots.size()) {
      return null;
    }
    return spots.get(commandIndex);
  }

  public int size() {
    return bySceneryId.size();
  }
}
