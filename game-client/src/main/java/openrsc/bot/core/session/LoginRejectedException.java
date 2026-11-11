package openrsc.bot.core.session;

import java.io.IOException;
import openrsc.bot.core.login.LoginResponse;

public final class LoginRejectedException extends IOException {

    private final int code;

    public LoginRejectedException(String username, int code, LoginResponse response) {
        super("login rejected for " + username + ": code=" + code + " detail=" + response.detail());
        this.code = code;
    }

    public int code() {
        return code;
    }
}
