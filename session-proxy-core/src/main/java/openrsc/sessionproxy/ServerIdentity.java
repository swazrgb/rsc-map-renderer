package openrsc.sessionproxy;

import java.io.IOException;
import java.math.BigInteger;
import openrsc.bot.core.login.PreLoginConfigs;

/** A game server's address, and the name and login key its configs packet must carry for the proxy to use it. */
record ServerIdentity(String serverName, String host, int port, BigInteger modulus, BigInteger exponent) {

    /**
     * Throws unless {@code configs} name this server and carry its key.
     *
     * @throws IOException naming what differs
     */
    void check(PreLoginConfigs configs) throws IOException {
        if (!serverName.equals(configs.serverName())) {
            throw new IOException(
                    host + ":" + port + " is \"" + configs.serverName() + "\", not \"" + serverName + "\"");
        }
        if (!modulus.equals(configs.rsaModulus()) || !exponent.equals(configs.rsaExponent())) {
            throw new IOException(host + ":" + port + " does not carry the login key of " + serverName);
        }
    }
}
