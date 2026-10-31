package openrsc.gamedata.world;

/**
 * The two collision overlays of one view of the world. A {@link CollisionMap} method taking this
 * reads the layer its question needs: walking reads {@link #walkOverlay}, reach and line of sight
 * read {@link #losOverlay}. A {@code null} layer reads the landscape as built.
 */
public interface Overlays {

    /** No view: both layers {@code null}. */
    Overlays NONE = new Overlays() {
        @Override
        public LocalOverlay walkOverlay() {
            return null;
        }

        @Override
        public LocalOverlay losOverlay() {
            return null;
        }

        @Override
        public String toString() {
            return "Overlays.NONE";
        }
    };

    /** Edges as a walk reads them: a door the route may open on its way is not a wall. */
    LocalOverlay walkOverlay();

    /** Edges as the server's collision has them, every closed door a wall. */
    LocalOverlay losOverlay();
}
