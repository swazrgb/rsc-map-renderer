package openrsc.nav;

/** Thrown when the duration of a {@link TravelTime} that has no route is read. */
public final class NoRouteException extends RuntimeException {

    public NoRouteException() {
        super("no route");
    }
}
