package orsc.graphics.two.SpriteArchive;

import java.util.ArrayList;
import java.util.List;

public class Subspace {

    private String name = "";
    private List<Entry> entryList = new ArrayList<>();

    @Override
    public String toString() {
        return getName();
    }

    public Subspace(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public List<Entry> getEntryList() {
        return entryList;
    }
}
