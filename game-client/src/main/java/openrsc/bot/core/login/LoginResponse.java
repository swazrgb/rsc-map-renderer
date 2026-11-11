package openrsc.bot.core.login;

import java.time.Duration;

public record LoginResponse(int code, Status status, Duration retryAfter, String detail) {

    public enum Status {
        SUCCESS,
        RETRY,
        FATAL
    }

    public static LoginResponse of(int code) {
        if ((code & 0x40) != 0) {
            return new LoginResponse(code, Status.SUCCESS, Duration.ZERO, "ok");
        }
        return switch (code) {
            case 3 -> new LoginResponse(code, Status.FATAL, Duration.ZERO, "invalid username or password");
            case 4 -> new LoginResponse(code, Status.RETRY, Duration.ofSeconds(5), "account already logged in");
            case 5 -> new LoginResponse(code, Status.FATAL, Duration.ZERO, "client has been updated");
            case 6 -> new LoginResponse(code, Status.RETRY, Duration.ofMinutes(6), "one character per session");
            case 7 -> new LoginResponse(code, Status.RETRY, Duration.ofMinutes(6), "login attempts exceeded");
            case 8 -> new LoginResponse(code, Status.RETRY, Duration.ofSeconds(5), "server rejected session");
            case 9 -> new LoginResponse(code, Status.RETRY, Duration.ofSeconds(5), "unable to login");
            case 10 -> new LoginResponse(code, Status.RETRY, Duration.ofSeconds(10), "username already in use");
            case 11 -> new LoginResponse(code, Status.RETRY, Duration.ofMinutes(6), "account temporarily disabled");
            case 12 -> new LoginResponse(code, Status.FATAL, Duration.ZERO, "account permanently disabled");
            case 14 -> new LoginResponse(code, Status.RETRY, Duration.ofSeconds(5), "world full");
            case 15, 16, 17, 18, 20, 21, 22, 23, 24, 25 ->
                new LoginResponse(code, Status.RETRY, Duration.ofMinutes(6), "generic server error " + code);
            default -> new LoginResponse(code, Status.RETRY, Duration.ofMinutes(1), "unhandled response " + code);
        };
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
