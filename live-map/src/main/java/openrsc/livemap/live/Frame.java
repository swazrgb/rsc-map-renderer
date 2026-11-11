package openrsc.livemap.live;

/** One event of the map's stream, sent to a viewer as {@code {"e": event, "d": data}}. */
public record Frame(String event, Object data) {}
