package openrsc.nav;

/**
 * How long a trip takes, or that there is no route. The duration is read only through
 * {@link #deci()} and {@link #ticks()}, which throw {@link NoRouteException} when there is no route,
 * or through {@link #deciOrElse} and {@link #ticksOrElse}, where the caller says what no route means.
 *
 * <p>The unit is the deci-tick, 10 to a tick. Ordered by duration, with no route last, so the least
 * of several is the nearest reachable one.
 */
public final class TravelTime implements Comparable<TravelTime> {

    public static final TravelTime NO_ROUTE = new TravelTime(-1);

    public static final TravelTime ZERO = new TravelTime(0);

    private final int deci;

    private TravelTime(int deci) {
        this.deci = deci;
    }

    /** From deci-ticks, any negative value meaning no route. */
    public static TravelTime ofDeci(int deci) {
        return deci < 0 ? NO_ROUTE : deci == 0 ? ZERO : new TravelTime(deci);
    }

    /**
     * @throws IllegalArgumentException when {@code ticks} is negative
     * @throws ArithmeticException when {@code ticks} overflows as deci-ticks
     */
    public static TravelTime ofTicks(int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("negative ticks " + ticks + "; no route is NO_ROUTE");
        }
        return ofDeci(Math.multiplyExact(ticks, 10));
    }

    public boolean isReachable() {
        return deci >= 0;
    }

    /** The duration in deci-ticks; throws {@link NoRouteException} when there is no route. */
    public int deci() {
        if (deci < 0) {
            throw new NoRouteException();
        }
        return deci;
    }

    /** The duration in whole ticks, rounded down; throws {@link NoRouteException} when there is no route. */
    public int ticks() {
        return deci() / 10;
    }

    public int deciOrElse(int ifNoRoute) {
        return deci >= 0 ? deci : ifNoRoute;
    }

    public int ticksOrElse(int ifNoRoute) {
        return deci >= 0 ? deci / 10 : ifNoRoute;
    }

    int raw() {
        return deci;
    }

    /** Whether there is a route and it takes at most {@code maxDeci} deci-ticks. */
    public boolean within(int maxDeci) {
        return deci >= 0 && deci <= maxDeci;
    }

    @Override
    public int compareTo(TravelTime o) {
        return Integer.compareUnsigned(deci, o.deci);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TravelTime t && t.deci == deci;
    }

    @Override
    public int hashCode() {
        return deci;
    }

    @Override
    public String toString() {
        return deci < 0 ? "no route" : deci + " deci";
    }
}
