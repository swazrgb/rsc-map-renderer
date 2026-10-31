package openrsc.nav;

/**
 * A {@link Route} laid out over the map for drawing, from {@link Planner#trace}: parallel arrays of
 * the tiles it passes through in walk order, the first being where the trace starts.
 *
 * <p>{@code jump[i]} marks a tile not walked to from the one before: a hop's landing, or the far
 * end of a stretch the trace could not search and drew straight. A renderer breaks the line there.
 */
public record TracedRoute(int[] xs, int[] ys, boolean[] jump) {

    public int size() {
        return xs.length;
    }
}
