package openrsc.livemap.stream;

import org.jspecify.annotations.Nullable;

/** A topic read that failed: an HTTP status and the error text a viewer shows. */
public final class TopicException extends RuntimeException {

    private final int status;

    public TopicException(int status, @Nullable String reason) {
        super(reason, null, false, false);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public @Nullable String reason() {
        return getMessage();
    }
}
