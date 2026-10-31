package openrsc.nav;

import java.util.Arrays;
import java.util.OptionalInt;

/** {@link TravelTime}s for a list of destinations, by the destination's index in that list. */
public final class TravelTimes {

    private static final TravelTimes EMPTY = new TravelTimes(new int[0]);

    private final int[] deci;

    private TravelTimes(int[] deci) {
        this.deci = deci;
    }

    /** From deci-ticks, negative meaning no route. Takes {@code deci} over: the caller must not change it after. */
    public static TravelTimes ofDeci(int[] deci) {
        return deci.length == 0 ? EMPTY : new TravelTimes(deci);
    }

    /** A batch of {@code size} filled in one at a time, every entry {@link TravelTime#NO_ROUTE} until set. */
    public static Builder builder(int size) {
        return new Builder(size);
    }

    /** See {@link #builder}. Single use: nothing may be set after {@link #build}. */
    public static final class Builder {
        private int[] deci;

        private Builder(int size) {
            deci = new int[size];
            Arrays.fill(deci, -1);
        }

        public Builder set(int i, TravelTime t) {
            deci[i] = t.raw();
            return this;
        }

        public TravelTimes build() {
            TravelTimes out = ofDeci(deci);
            deci = null;
            return out;
        }
    }

    public int size() {
        return deci.length;
    }

    public TravelTime get(int i) {
        return TravelTime.ofDeci(deci[i]);
    }

    /** These entries followed by {@code more}'s. */
    public TravelTimes concat(TravelTimes more) {
        if (more.deci.length == 0) {
            return this;
        }
        int[] both = Arrays.copyOf(deci, deci.length + more.deci.length);
        System.arraycopy(more.deci, 0, both, deci.length, more.deci.length);
        return new TravelTimes(both);
    }

    /** The index of the shortest reachable entry, the first of equals; empty when none is reachable. */
    public OptionalInt nearest() {
        int best = -1;
        for (int i = 0; i < deci.length; i++) {
            if (deci[i] >= 0 && (best < 0 || deci[i] < deci[best])) {
                best = i;
            }
        }
        return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TravelTimes t && Arrays.equals(t.deci, deci);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(deci);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < deci.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(get(i));
        }
        return sb.append(']').toString();
    }
}
