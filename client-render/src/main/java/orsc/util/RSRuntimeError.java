package orsc.util;

public final class RSRuntimeError extends RuntimeException {
    /**
     *
     */
    private static final long serialVersionUID = 1L;

    public String message;

    RSRuntimeError(Throwable error, String message) {
        super(null, error);
        this.message = message;
    }
}
