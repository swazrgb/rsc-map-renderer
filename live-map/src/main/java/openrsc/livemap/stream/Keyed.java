package openrsc.livemap.stream;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Topic records from a list: one per row in list order, keyed by {@code key}; a repeated key gets a suffix. */
public final class Keyed {

    private Keyed() {}

    public static <T> Map<String, T> by(Iterable<? extends T> rows, Function<? super T, ?> key) {
        Map<String, T> out = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (T row : rows) {
            String k = String.valueOf(key.apply(row));
            int n = seen.merge(k, 1, Integer::sum);
            out.put(n == 1 ? k : k + "~" + n, row);
        }
        return out;
    }
}
