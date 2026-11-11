package openrsc.livemap.items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rate per hour rounded as it is shown: to whole units below 1k, hundreds below 10k, thousands below 1m,
 * hundred-thousands below 10m and millions above. A decaying rate moves on every sample, so a record holding it
 * unrounded never equals its last read.
 */
public final class PerHour {

    private PerHour() {}

    public static double shown(double perHour) {
        double a = Math.abs(perHour);
        double step = a >= 1e7 ? 1e6 : a >= 1e6 ? 1e5 : a >= 1e4 ? 1e3 : a >= 1e3 ? 100 : 1;
        return Math.round(perHour / step) * step;
    }

    public static Map<String, Double> shown(Map<String, Double> perHour) {
        Map<String, Double> out = new LinkedHashMap<>();
        perHour.forEach((k, v) -> out.put(k, shown(v)));
        return out;
    }
}
