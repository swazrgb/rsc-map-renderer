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
 * {@code TileDefs.json} loader; collision map consults {@code ObjectType}, the web UI's terrain
 * renderer consults {@code Colour} + {@code TileValue}.
 */
public final class TileDefs {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entry(
      @JsonProperty("ObjectType") int objectType,
      @JsonProperty("Colour") int colour,
      @JsonProperty("TileValue") int tileValue) {

  }

  private final Entry[] byId;

  private TileDefs(Entry[] byId) {
    this.byId = byId;
  }

  public static TileDefs load(Path file) throws IOException {
    ObjectMapper m = new ObjectMapper();
    List<Entry> list = m.readValue(
        Files.readAllBytes(file),
        m.getTypeFactory().constructCollectionType(List.class, Entry.class));
    return new TileDefs(list.toArray(Entry[]::new));
  }

  /**
   * Loads the server's authoritative {@code defs/TileDef.xml} ({@code <TileDef-array>}, positional
   * ids; the server's {@code unknown} field is what the old JSON dump called {@code TileValue}).
   */
  public static TileDefs loadXml(Path file) {
    List<XmlTree> defs =
        XmlTree.parse(file).children("TileDef");
    Entry[] arr = new Entry[defs.size()];
    for (int id = 0; id < defs.size(); id++) {
      var d = defs.get(id);
      arr[id] = new Entry(d.intOf("objectType", 0), d.intOf("colour", 0),
          d.intOf("unknown", 0));
    }
    return new TileDefs(arr);
  }

  public int objectType(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].objectType() : 0;
  }

  public int colour(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].colour() : 0;
  }

  public int tileValue(int id) {
    return (id >= 0 && id < byId.length) ? byId[id].tileValue() : 0;
  }

  public int size() {
    return byId.length;
  }
}
