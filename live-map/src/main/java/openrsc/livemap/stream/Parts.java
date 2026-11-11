package openrsc.livemap.stream;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A record or map laid out as topic records, so that a viewer is sent only the parts that changed. Keys, from which a
 * viewer assembles the object again as Jackson would write it:
 *
 * <ul>
 *   <li>{@code name}: a field, under its JSON name and left out as Jackson would leave it out;
 *   <li>{@code name[]} and {@code name[]/key}: a list field, one record per element;
 *   <li>{@code name{}} and {@code name/key}: a map field, one record per entry;
 *   <li>{@code name.} and {@code name.child}: a nested record, one record per field.
 * </ul>
 */
public final class Parts {

    private static final Integer MARKER = 0;

    private final Map<String, Object> parts = new LinkedHashMap<>();

    private Parts() {}

    public static Parts of(Record record) {
        Parts p = new Parts();
        p.addFields("", record);
        return p;
    }

    public static Parts of(Map<String, ?> map) {
        Parts p = new Parts();
        p.parts.putAll(map);
        return p;
    }

    /** Splits the list field {@code name} into one record per element, keyed by {@code key}. */
    @SuppressWarnings("unchecked")
    public <T> Parts list(String name, Function<? super T, ?> key) {
        if (!(parts.get(name) instanceof List<?> list)) {
            return this;
        }
        parts.remove(name);
        parts.put(name + "[]", MARKER);
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Object element : list) {
            String k = String.valueOf(key.apply((T) element));
            int n = seen.merge(k, 1, Integer::sum);
            parts.put(name + "[]/" + (n == 1 ? k : k + "~" + n), element);
        }
        return this;
    }

    /** Splits the map field {@code name} into one record per entry. */
    public Parts map(String name) {
        if (!(parts.get(name) instanceof Map<?, ?> map)) {
            return this;
        }
        parts.remove(name);
        parts.put(name + "{}", MARKER);
        map.forEach((k, v) -> parts.put(name + "/" + k, v));
        return this;
    }

    /** Splits the record field {@code name} into one record per field, named {@code name.field}. */
    public Parts nest(String name) {
        if (!(parts.get(name) instanceof Record nested)) {
            return this;
        }
        parts.remove(name);
        parts.put(name + ".", MARKER);
        addFields(name + ".", nested);
        return this;
    }

    /** Leaves out the fields {@code names}. */
    public Parts drop(String... names) {
        for (String name : names) {
            parts.remove(name);
        }
        return this;
    }

    public Map<String, Object> toMap() {
        return parts;
    }

    private void addFields(String prefix, Record record) {
        JsonInclude.Include classRule = rule(record.getClass().getAnnotation(JsonInclude.class));
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            var accessor = c.getAccessor();
            if (accessor.isAnnotationPresent(JsonIgnore.class)) {
                continue;
            }
            Object value;
            try {
                value = accessor.invoke(record);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot read " + c.getName() + " of " + record.getClass(), e);
            }
            JsonInclude.Include fieldRule = rule(accessor.getAnnotation(JsonInclude.class));
            if (omitted(fieldRule != null ? fieldRule : classRule, value)) {
                continue;
            }
            JsonProperty renamed = accessor.getAnnotation(JsonProperty.class);
            String name = renamed != null && !renamed.value().isEmpty() ? renamed.value() : c.getName();
            parts.put(prefix + name, value);
        }
    }

    private static JsonInclude.@Nullable Include rule(@Nullable JsonInclude include) {
        return include == null || include.value() == JsonInclude.Include.USE_DEFAULTS ? null : include.value();
    }

    private static boolean omitted(JsonInclude.@Nullable Include rule, @Nullable Object value) {
        if (rule == null || rule == JsonInclude.Include.ALWAYS) {
            return false;
        }
        if (value == null) {
            return true;
        }
        if (rule != JsonInclude.Include.NON_EMPTY) {
            return false;
        }
        return (value instanceof Collection<?> c && c.isEmpty())
                || (value instanceof Map<?, ?> m && m.isEmpty())
                || (value instanceof CharSequence s && s.isEmpty());
    }
}
