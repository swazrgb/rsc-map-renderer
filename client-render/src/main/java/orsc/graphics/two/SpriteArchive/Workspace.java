package orsc.graphics.two.SpriteArchive;

import java.util.ArrayList;
import java.util.List;

public class Workspace {

    private List<Subspace> subspaces = new ArrayList<>();

    public Workspace() {}

    public List<Subspace> getSubspaces() {
        return this.subspaces;
    }
}
