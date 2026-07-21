package openrsc.gamedata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code NpcLocs.json} loader — every NPC spawn the server populates, each with a roam rectangle.
 * Mirrors {@code WorldPopulator.loadNpcLocs} (the authentic {@code NpcLocs.json}; with
 * {@code based_map_data >= 28} the server does not use the {@code *14}/{@code *27} variants, and on
 * a members world the F2P {@code isP2P}/{@code isMembers} filters are skipped, so the whole file
 * spawns). The server's {@code NpcBehavior.handleRoam} walks each NPC to a random {@code
 * walkablePoint} inside {@code [min, max]}, so that rectangle is the NPC's reachable footprint.
 *
 * <p>Server {@code Point} stores {@code (X, Y)}; the bot's {@code (x, z)} are the same axes
 * ({@code Y == z}). Custom/quest locs ({@code loadCustomLocs}) are not loaded — this is the
 * authentic spawn set, which is what the wilderness-avoidance tooling reasons about.
 */
public final class NpcLocs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Pos(@JsonProperty("X") int x, @JsonProperty("Y") int z) {

  }

  /** One spawn: NPC id, its start tile, and the {@code [min, max]} roam rectangle. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Spawn(@JsonProperty("id") int id,
                      @JsonProperty("start") Pos start,
                      @JsonProperty("min") Pos min,
                      @JsonProperty("max") Pos max) {

  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Root(@JsonProperty("npclocs") List<Spawn> npclocs) {

  }

  public static List<Spawn> load(Path file) throws IOException {
    ObjectMapper m = new ObjectMapper();
    Root root = m.readValue(Files.readAllBytes(file), Root.class);
    return root.npclocs();
  }

  private NpcLocs() {
  }
}
