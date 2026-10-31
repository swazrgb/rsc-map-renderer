package openrsc.gamedata.defs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.xml.XmlTree;

/**
 * {@code ObjectFishing.xml}: fishing spots by scenery id, one {@link Spot} per command of the
 * spot.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L210">EntityHandler.load</a>
 */
public record FishingDefs(Map<Integer, List<Spot>> bySceneryId) {

    /**
     * One fish a spot gives. {@code lowRate} and {@code highRate} are its success chance out of 256 at
     * level 1 and at level 99, interpolated between.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L369">Formulae.interp</a>
     */
    public record Fish(int fishId, int requiredLevel, int exp, int lowRate, int highRate) {}

    /**
     * What one command of a spot catches. {@code fish} is in file order, highest level first; with
     * {@code cascade} 1 their chances are computed together in that order. {@code depletion} is the
     * chance out of 250 a catch empties the spot, on a world with {@code fishing_spots_depletable};
     * {@code respawnTime} is in ticks.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/ObjectFishingDef.java#L90">ObjectFishingDef.calculateFishRates</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/fishing/Fishing.java#L377">Fishing.handleDepletableFishing</a>
     */
    public record Spot(int netId, int baitId, int depletion, int respawnTime, int cascade, List<Fish> fish) {}

    public static FishingDefs from(XmlTree root) {
        Map<Integer, List<Spot>> m = new HashMap<>();
        ExtrasXml.eachEntry(root, (id, arr) -> {
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

    public int size() {
        return bySceneryId.size();
    }
}
